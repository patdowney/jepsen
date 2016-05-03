(ns jepsen.mongodb.core
  (:require [clojure [pprint :refer :all]
                     [string :as str]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [debug info warn]]
            [clojure.walk :as walk]
            [jepsen [core      :as jepsen]
                    [db        :as db]
                    [util      :as util :refer [meh timeout]]
                    [control   :as c :refer [|]]
                    [client    :as client]
                    [checker   :as checker]
                    [generator :as gen]
                    [nemesis   :as nemesis]
                    [store     :as store]
                    [report    :as report]
                    [tests     :as tests]]
            [jepsen.control [net :as net]
                            [util :as cu]]
            [jepsen.os.debian :as debian]
            [jepsen.mongodb.mongo :as m]
            [knossos [core :as knossos]
                     [model :as model]]
            [cheshire.core :as cheshire])
  (:import (clojure.lang ExceptionInfo)
           (jepsen.checker Checker)))


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

(defn perf-dump
  "Spits out performance stats"
  []
  (reify Checker
    (check [_ test model history opts]
      (if-let [perfdumpfile (:perfdumpfile test)]
        (do
          (info "running custom history dump to " perfdumpfile)
          (with-open [out (io/writer perfdumpfile)]
            (cheshire/generate-stream (util/history->latencies history) out {:pretty true}))))
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
           :os              debian/os
           :checker         (checkers)
           :nemesis         nemesis/noop)
    opts))
