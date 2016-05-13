(ns jepsen.mongodb.reports
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
    [clojure.java.io :as io]
    [cheshire.core :as cheshire])
  (:import (jepsen.checker Checker)))

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
