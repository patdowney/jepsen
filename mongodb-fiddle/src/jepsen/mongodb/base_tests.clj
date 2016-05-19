(ns jepsen.mongodb.base-tests
  (:require
    [clojure.tools.logging :refer [debug info warn trace spy]]
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
    [jepsen.os.debian :as debian]
    [jepsen.mongodb.null_os :as null_os]
    [jepsen.mongodb.mongod :as mongod]
    [jepsen.net :refer [iptables-old]]))

(defn test-
  "Constructs a test with the given name prefixed by 'mongodb ', merging any
  given options. Special options for Mongo:

  :time-limit         How long do we run the test for?
  :storage-engine     Storage engine to use
  :protocol-version   Replication protocol version"
  [name opts]
  (merge
    (assoc tests/noop-test
      :name (str "mongodb-fiddle " name)
      :os (case (:os-flavour opts)
            :debian debian/os
            :null null_os/null_os)
      :db (mongod/db (:mongodb opts))
      :net iptables-old)                                    ; TODO - make this configurable, if/when we have different os's
    opts))