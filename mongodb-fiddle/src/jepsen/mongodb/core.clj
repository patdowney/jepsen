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
            [jepsen.net :refer [iptables-old]]
            [jepsen.control :refer [su]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as net]
            [jepsen.os.debian :as debian]
            [jepsen.mongodb.mongo :as m]
            [jepsen.mongodb.mongod :as mongod]
            [jepsen.mongodb.null_os :as null_os]
            [jepsen.mongodb.cluster :as cluster]
            [knossos [core :as knossos]
             [model :as model]]
            [cheshire.core :as cheshire]
)
  (:import (clojure.lang ExceptionInfo)
           (jepsen.checker Checker)
           (com.mongodb MongoCommandException)))

(defn db
  "MongoDB for a particular configuration"
  [mongodb-config]
  (reify db/DB
    (setup! [_ test node]
      (trace "setup! on " node)
      (if (:install mongodb-config)
        (mongod/install! node mongodb-config)
        (mongod/wipe! node mongodb-config true))                  ; stop and wipe
      ; TODO - should we be allowing users to not even stop Mongo?
      ; be good to read the docs in more detail for relevant versions, see what
      ; we can do without a restart.
      (if (:configure mongodb-config) (mongod/configure! node mongodb-config))
      (mongod/start! node mongodb-config)
      (cluster/join! node test))

    (teardown! [_ test node]
      (trace "teardown! on " node)
      (mongod/wipe! node mongodb-config))))


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
      :net iptables-old
      :nemesis (case (:nemesis-kind opts)
                 :partition (nemesis/partition-random-halves)
                 :noop nemesis/noop))
    opts))
