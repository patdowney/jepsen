(ns jepsen.mongodb.cluster
  (:require
    [clojure.tools.logging :refer [debug info warn trace spy]]
    [jepsen.mongodb [mongo :as m]
     [null_os :as null_os]]
    [jepsen.core :as jepsen]
    [jepsen.util :refer [meh timeout]])
  (:import (com.mongodb MongoCommandException)
           (clojure.lang ExceptionInfo)))

(defn replica-set-status
  "Returns the current replica set status."
  [conn]
  (try
    (m/admin-command! conn :replSetGetStatus 1)
    ; I have a feeling the "ExceptionInfo" version is out of date - admin-command doesn't convert exceptions for us!
    (catch MongoCommandException e                          ; lazy - just copying previous logic!  TODO: clean up and remove duplcation
      (condp re-find (.getErrorMessage e)
        ; Some of the time (but not all the time; why?) Mongo returns this error
        ; from replSetGetStatus as well!
        #"Received replSetInitiate - should come online shortly"
        nil

        ; This is a hint we should back off and retry; one of the nodes probably
        ; isn't fully alive yet.
        #"need all members up to initiate, not ok"
        (do (info "not all members alive yet; retrying replica set initiate"
                  (Thread/sleep 1000)
                  (replica-set-status conn)))
        ; Or by default re-throw
        (throw e))
      )
    (catch ExceptionInfo e
      (condp re-find (get-in (ex-data e) [:result "errmsg"])
        ; Some of the time (but not all the time; why?) Mongo returns this error
        ; from replSetGetStatus as well!
        #"Received replSetInitiate - should come online shortly"
        nil

        ; This is a hint we should back off and retry; one of the nodes probably
        ; isn't fully alive yet.
        #"need all members up to initiate, not ok"
        (do (info "not all members alive yet; retrying replica set initiate"
                  (Thread/sleep 1000)
                  (replica-set-status conn)))
        ; Or by default re-throw
        (throw e)))
    ))

(defn replica-set-initiate!
  "Initialize a replica set on a node."
  [conn config]
  (try
    (m/admin-command! conn :replSetInitiate config)
    (catch ExceptionInfo e
      (condp re-find (get-in (ex-data e) [:result "errmsg"])
        ; Some of the time (but not all the time; why?) Mongo returns this error
        ; from replsetinitiate, which is, as far as I can tell, not actually an
        ; error (?)
        #"Received replSetInitiate - should come online shortly"
        nil

        ; This is a hint we should back off and retry; one of the nodes probably
        ; isn't fully alive yet.
        #"need all members up to initiate, not ok"
        (do (info "not all members alive yet; retrying replica set initiate"
                  (Thread/sleep 1000)
                  (replica-set-initiate! conn config)))

        ; Or by default re-throw
        (throw e)))))

(defn replica-set-master?
  "What's this node's replset role?"
  [conn]
  (m/admin-command! conn :isMaster 1))

(defn replica-set-config
  "Returns the current replset config."
  [conn]
  (m/admin-command! conn :replSetGetConfig 1))

(defn replica-set-reconfigure!
  "Apply new configuration for a replica set."
  [conn conf]
  (m/admin-command! conn :replSetReconfig conf))

(defn node+port->node
  "Take a mongo \"n1:27107\" string and return just the node as a keyword:
  :n1."
  [s]
  (keyword ((re-find #"([\w\.-]+?):" s) 1)))                ; TODO - split on :

(defn primaries
  "What nodes does this conn think are primaries?"
  [conn]
  (->> (replica-set-status conn)
       :members
       (filter #(= "PRIMARY" (:stateStr %)))
       (map :name)
       (map node+port->node)))

(defn primary
  "Which single node does this conn think the primary is? Throws for multiple
  primaries, cuz that sounds like a fun and interesting bug, haha."
  [conn]
  (let [ps (primaries conn)]
    (when (< 1 (count ps))
      (throw (IllegalStateException.
               (str "Multiple primaries known to "
                    conn
                    ": "
                    ps))))

    (first ps)))


(defn await-conn
  "Block until we can connect to the given node. Returns a connection to the
  node."
  [mongodb-config node]
  (timeout (* 100 1000)
           (throw (ex-info "Timed out trying to connect to MongoDB"
                           {:node node}))
           (loop []
             (or (try
                   (let [conn (m/client mongodb-config node)]
                     (try
                       (trace "connected to mongodb - dbs: " (pr-str (seq (.listDatabaseNames conn))))
                       conn
                       ; Don't leak clients when they fail
                       (catch Throwable t
                         (.close conn)
                         (throw t))))
                   ;                   (catch com.mongodb.MongoServerSelectionException e
                   ;                     nil))
                   ; Todo: figure out what Mongo 3.x throws when servers
                   ; aren't ready yet
                   )
                 ; If we aren't ready, sleep and retry
                 (do
                   (Thread/sleep 1000)
                   (recur))))))

(defn await-primary
  "Block until a primary is known to the current node."
  [conn]
  (while (not (primary conn))
    (Thread/sleep 1000)))

(defn await-join
  "Block until all nodes in the test are known to this connection's replset
  status"
  [test conn]
  (while (try (not= (set (:nodes test))
                    (->> (replica-set-status conn)
                         :members
                         (map :name)
                         (map node+port->node)
                         set))
              (catch ExceptionInfo e
                (if (re-find #"should come online shortly"
                             (get-in (ex-data e) [:result "errmsg"]))
                  true
                  (throw e))))
    (Thread/sleep 1000)))

(defn target-replica-set-config
  "Generates the config for a replset in a given test."
  [test]
  (case (:major-version (:mongodb test))
    2 (do
        {:_id      "jepsen"
         :settings {:heartbeatTimeoutSecs 20}
         :members  (->> test
                        :nodes
                        (map-indexed (fn [i node]
                                       {:_id  i
                                        :host (str (name node) ":27017")})))})
    3 (do
        (assert (integer? (:protocol-version (:mongodb test))))
        {:_id             "jepsen"
         :protocolVersion (:protocol-version (:mongodb test))
         :settings        {:heartbeatTimeoutSecs 20}
         :members         (->> test
                               :nodes
                               (map-indexed (fn [i node]
                                              {:_id  i
                                               :host (str (name node) ":27017")})))})))

(defn join!
  "Join nodes into a replica set. Blocks until any primary is visible to all
  nodes which isn't really what we want but oh well."
  [node test]
  (debug "joining nodes into replica set")
  ; Gotta have all nodes online for this. Delightfully, Mongo won't actually
  ; bind to the port until well *after* the init script startup process
  ; returns. This would be fine, except that  if a node isn't ready to join,
  ; the initiating node will just hang indefinitely, instead of figuring out
  ; that the node came online a few seconds later.
  (.close (await-conn (:mongodb test) node))
  (jepsen/synchronize test)

  ; Initiate RS
  (when (= node (jepsen/primary test))
    (with-open [conn (await-conn (:mongodb test) node)]
      (info node "Initiating replica set")
      (replica-set-initiate! conn (target-replica-set-config test))

      (info node "Jepsen primary waiting for cluster join")
      (await-join test conn)
      (info node "Jepsen primary waiting for mongo election")
      (await-primary conn)
      (info node "Primary ready.")))

  ; For reasons I really don't understand, you have to prevent other nodes
  ; from checking the replset status until *after* we initiate the replset on
  ; the primary--so we insert a barrier here to make sure other nodes don't
  ; wait until primary initiation is complete.
  (jepsen/synchronize test)

  ; For other reasons I don't understand, you *have* to open a new set of
  ; connections after replset initation. I have a hunch that this happens
  ; because of a deadlock or something in mongodb itself, but it could also
  ; be a client connection-closing-detection bug.

  ; Amusingly, we can't just time out these operations; the client appears to
  ; swallow thread interrupts and keep on doing, well, something. FML.
  (with-open [conn (await-conn (:mongodb test) node)]
    (info node "waiting for cluster join")
    (await-join test conn)

    (info node "waiting for primary")
    (await-primary conn)

    (info node "primary is" (primary conn))
    (jepsen/synchronize test)))
