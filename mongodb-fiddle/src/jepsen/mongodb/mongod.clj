(ns jepsen.mongodb.mongod
  (:require
    [clojure.tools.logging :refer [debug info warn trace spy]]
    [jepsen [core :as jepsen]
     [db :as db]
     [util :refer [meh timeout]]
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
    [jepsen.mongodb.util :as util]

    [clojure.java.io :as io]))

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

(defn configure!
  "Deploy configuration files to the node."
  [node {:keys [flavour] :as mongodb-config}]
  (trace "configuring mongo on" node "flavour" flavour)
  (case flavour
    :original (c/sudo (:username mongodb-config)
                (c/exec :echo (-> (:conf-file mongodb-config)
                                  io/resource
                                  slurp
                                  (util/replace-all (:conf-replacements mongodb-config)))
                        :> "/opt/mongodb/mongod.conf"))
    :terraform (c/su
                 (c/exec :echo (-> (:conf-file mongodb-config)
                                   io/resource
                                   slurp
                                   (util/replace-all (:conf-replacements mongodb-config)))
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
    :terraform (c/su
                 (c/exec :service :mongod :start))))

(defn stop!
  "Stops Mongod"
  [node {:keys [flavour] :as mongodb-config}] ; this works now, as long as you just use pidfile.
  ; TODO - patch jepsen to not use killall?
  (trace "stopping mongod on" node)
  (case flavour
    :original (c/sudo (:username mongodb-config)
                (meh (c/exec :kill (c/lit "$(pgrep mongod)")))
                (warn "sleeping 5s as we aren't checking for mongo death well")
                (Thread/sleep 5000)
                ; not needed: (cu/stop-daemon! "/opt/mongodb/pidfile")
                )
    :terraform (c/su
                 (c/exec :service :mongod :stop))))

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
