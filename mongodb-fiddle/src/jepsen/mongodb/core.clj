(ns jepsen.mongodb.core
  (:require [clojure [pprint :refer :all]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [debug info warn trace spy]]
            [clojure.walk :as walk]
            [jepsen [core :as jepsen]
             [db :as db]
             [util :as util :refer [meh timeout]]
             [control :as c :refer [|]]
             [client :as client]
             [checker :as checker]
             [generator :as gen]
             [nemesis :as nemesis]
             [store :as store]
             [report :as report]
             [tests :as tests]]
            [jepsen.control :refer [su]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as net]
            [jepsen.os.debian :as debian]
            [jepsen.mongodb [mongo :as m]
              [null_os :as null_os]]
            [knossos [core :as knossos]
             [model :as model]]
            [cheshire.core :as cheshire]
)
  (:import (clojure.lang ExceptionInfo)
           (jepsen.checker Checker)))

(defn install!
  "Installs a tarball from an HTTP URL"
  [node {:keys [tarball username] :as mongodb-config}]
  ; Add user
  (trace "Installing mongo")
  (su
    (cu/ensure-user! username)

    ; Download tarball
    (let [local-file (nth (re-find #"file://(.+)" tarball) 1)
          file (or local-file (c/cd "/tmp" (str "/tmp/" (cu/wget! tarball))))]
      (try
        (c/cd "/opt"
              ; Clean up old dir
              (c/exec :rm :-rf "mongodb")
              ; Extract and rename
              (c/exec :tar :xvf file)
              (c/exec :mv (c/lit "mongodb-linux-*") "mongodb")
              ; Create data dir
              (c/exec :mkdir :-p "mongodb/data")
              ; Permissions
              (c/exec :chown :-R (str username ":" username) "mongodb"))
        (catch RuntimeException e
          (condp re-find (.getMessage e)
            #"tar: Unexpected EOF"
            (if local-file
              ; Nothing we can do to recover here
              (throw (RuntimeException.
                       (str "Local tarball " local-file " on node " (name node)
                            " is corrupt: unexpected EOF.")))
              (do (info "Retrying corrupt tarball download")
                  (c/exec :rm :-rf file)
                  (install! node mongodb-config)))

            ; Throw by default
            (throw e)))))))

(defn replace-all [str replacement-map]
  (info "replacing with" replacement-map)
  (reduce (fn [str [k v]]
            (str/replace str (re-pattern k) v))
          str
          replacement-map))

(defn configure!
  "Deploy configuration files to the node."
  ; TODO - is this the normal way to determine read concerns? Seems strange to be in mongo config
  [node {:keys [flavour] :as mongodb-config}]
  (trace "configuring mongo on" node "flavour" flavour)
  (case flavour
    :original (c/sudo (:username mongodb-config)
               (c/exec :echo (-> (:conf-file mongodb-config)
                                 io/resource
                                 slurp
                                 (replace-all (:conf-replacements mongodb-config)))
                       :> "/opt/mongodb/mongod.conf"))
    :terraform (c/su
                 (c/exec :echo (-> (:conf-file mongodb-config)
                                   io/resource
                                   slurp
                                   (replace-all (:conf-replacements mongodb-config)))
                         :> "/etc/mongod.conf"))))

(defn start!
  "Starts Mongod"
  [node {:keys [flavour] :as mongodb-config}]
  (trace "starting mongod on" node)
  (case flavour
    :original (c/sudo (:username mongodb-config)
               (cu/start-daemon! {:logfile "/opt/mongodb/stdout.log"
                                  :pidfile "/opt/mongodb/pidfile"
                                  :chdir   "/opt/mongodb"}
                                 "/opt/mongodb/bin/mongod"
                                 :--config "/opt/mongodb/mongod.conf"))
    :terraform (c/sudo (:username mongodb-config)
                 (c/exec "/usr/bin/mongod"
                         :--config "/etc/mongod.conf"
                         :--fork))))

(defn stop!
  "Stops Mongod"
  [node mongodb-config] ; this works now, as long as you just use pidfile.
  ; TODO - patch jepsen to not use killall?
  (trace "stopping mongod on" node)
  (c/sudo (:username mongodb-config)
          (meh (c/exec :kill (c/lit "$(pgrep mongod)")))
          (warn "sleeping 5s as we aren't checking for mongo death well")
          (Thread/sleep 5000)
          ; not needed: (cu/stop-daemon! "/opt/mongodb/pidfile")
          ))

(defn wipe!
  "Shuts down MongoDB and wipes log files - and optionally data files"
  ([node mongodb-config] (wipe! node mongodb-config false))
  ([node {:keys [flavour] :as mongodb-config} with-data]
   (trace "wiping mongo data on" node)
   (stop! node mongodb-config)
   (case flavour
     :original (c/sudo (:username mongodb-config)
                (if with-data (c/exec :rm :-rf (c/lit "/opt/mongodb/data/*")))
                (c/exec :rm :-rf (c/lit "/opt/mongodb/*.log")))
     :terraform (c/sudo (:username mongodb-config)
                  (if with-data (c/exec :rm :-rf (c/lit "/var/lib/mongo/*")))
                  (c/exec :rm :-rf (c/lit "/var/log/mongodb/*.log"))))))


(defn mongo!
  "Run a Mongo shell command. Spits back an unparsable kinda-json string,
  because what else would 'printjson' do?"
  [cmd]
  (-> (c/exec :mongo :--quiet :--eval (str "printjson(" cmd ")"))))

;; Cluster setup

(defn replica-set-status
  "Returns the current replica set status."
  [conn]
  (m/admin-command! conn :replSetGetStatus 1))

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
  (assert (integer? (:protocol-version (:mongodb test))))
  {:_id             "jepsen"
   :protocolVersion (:protocol-version (:mongodb test))
   :settings { :heartbeatTimeoutSecs 20 }
   :members         (->> test
                         :nodes
                         (map-indexed (fn [i node]
                                        {:_id  i
                                         :host (str (name node) ":27017")})))})

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

(defn db
  "MongoDB for a particular configuration"
  [mongodb-config]
  (reify db/DB
    (setup! [_ test node]
      (trace "setup! on " node)
      (if (:install mongodb-config)
        (install! node mongodb-config)
        (wipe! node mongodb-config true))                  ; stop and wipe
      ; TODO - should we be allowing users to not even stop Mongo?
      ; be good to read the docs in more detail for relevant versions, see what
      ; we can do without a restart.
      (if (:configure mongodb-config) (configure! node mongodb-config))
      (start! node mongodb-config)
      (join! node test))

    (teardown! [_ test node]
      (trace "teardown! on " node)
      (wipe! node mongodb-config))))


(defmacro with-errors
  "Takes an invocation operation, a set of idempotent operation functions which
  can be safely assumed to fail without altering the model state, and a body to
  evaluate. Catches MongoDB errors and maps them to failure ops matching the
  invocation."
  [op idempotent-ops & body]
  `(let [error-type# (if (~idempotent-ops (:f ~op))
                       :fail
                       :info)]
     (try
       ~@body
       (catch com.mongodb.MongoNotPrimaryException e#
         (assoc ~op :type :fail, :error :not-primary))

       ; A network error is indeterminate
       (catch com.mongodb.MongoSocketReadException e#
         (assoc ~op :type error-type# :error :socket-read))

       (catch com.mongodb.MongoSocketReadTimeoutException e#
         (assoc ~op :type error-type# :error :socket-read)))))

(def nanos-offset "this server's approximate offset between nanoTime and epoch time in ms"
  (let [ctms (System/currentTimeMillis)
        nt (System/nanoTime)]
    (- (* ctms 1000000) nt)))

(defn nanos->epochms "convert a time from nanoTime to system ms" [nanos]
  (if nanos
    (if-let [rto util/*relative-time-origin*]
      (long (/ (+ nanos nanos-offset rto) 1000000))
      (throw (ex-info "Can't calculate epoch times if relative-time-origin isn't set" {})))))

(defn fix-ts [history-row]
  (let [start-nanos (:time history-row)
        complete-nanos (:time (:completion history-row))]
    (as-> history-row h
        (assoc h :timestamp (nanos->epochms start-nanos))
        (if complete-nanos
          (assoc-in h [:completion :timestamp] (nanos->epochms complete-nanos))
          h))))

(defn perf-dump
  "Spits out performance stats"
  []
  (reify Checker
    (check [_ test model history opts]
      (if-let [perfdumpfile (:perfdumpfile test)]
        (let [filename (store/path! test (:subdirectory opts)
                                    perfdumpfile)
              fixed-history (map fix-ts (util/history->latencies history))]
          (info "running custom history dump to " filename)
          (with-open [out (io/writer filename)]
            (cheshire/generate-stream fixed-history out {:pretty true}))))
      {:valid? true})))

(defn checkers [] (checker/compose {:perf-dump (perf-dump)
                            :latency-graph (checker/latency-graph)
                            :rate-graph    (checker/rate-graph)}))

(defn test-
  "Constructs a test with the given name prefixed by 'mongodb ', merging any
  given options. Special options for Mongo:

  :time-limit         How long do we run the test for?
  :storage-engine     Storage engine to use
  :protocol-version   Replication protocol version"
  [name opts]
  (merge
    (assoc tests/noop-test
      :name            (str "mongodb-fiddle " name )
      :os (case (:os-flavour opts)
            :debian debian/os
            :null null_os/null_os)
      :db              (db (:mongodb opts))
      :checker         (checkers)
      :nemesis         nemesis/noop)
    opts))
