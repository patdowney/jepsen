(ns jepsen.mongodb.util
  (:require [clojure.tools.logging :refer [debug info warn trace spy]]
            [jepsen.mongodb.reports :as reports]
            [puppetlabs.structured-logging.core :refer [maplog]]
            [clojure.string :as str]
            [jepsen.util :as jutil]))

(defn replace-all [str replacement-map]
  (info "replacing with" replacement-map)
  (reduce (fn [str [k v]]
            (str/replace str (re-pattern k) v))
          str
          replacement-map))

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
         (trace e# "Mongo Exception caught - turning into internal status")
         (assoc ~op :type :fail, :error :not-primary))

       ; A network error is indeterminate
       (catch com.mongodb.MongoSocketReadException e#
         (trace e# "Mongo Exception caught - turning into internal status")
         (assoc ~op :type error-type# :error :socket-read))

       (catch com.mongodb.MongoSocketReadTimeoutException e#
         (trace e# "Mongo Exception caught - turning into internal status")
         (assoc ~op :type error-type# :error :socket-read)))))

(defn timing-data [start end]
  {:start    (reports/nanos->epochms start)
   :end      (reports/nanos->epochms end)
   :duration (- end start)})

(defn op-data [op no-values result]
  (let [value-keyword (keyword (str "value-" (name (:f op))))
        value (if no-values :removed (:value op))]
    {:process      (:process op)
     :optype       (:type op)
     :responsetype (:type result)
     :f            (:f op)
     value-keyword value
     :error        (or (:error result) "none")}))

(defmacro with-timing-logs-ex [op {:keys [no-values]} & body]
  `(try
     (let [start# (:time ~op)
           result# ~@body
           end# (jutil/relative-time-nanos)]
       (maplog [:stash :info] {:client-data (merge (timing-data start# end#)
                                                   (op-data ~op ~no-values result#))}
               "client invoke")
       result#)
     (catch Exception e#
       (warn e# "Uncaught exception seen during invoke! call")
       (let [end# (jutil/relative-time-nanos)]
         (maplog [:stash :warn]
                 {:client-data (merge (timing-data (:time ~op) end#)
                                      (op-data ~op ~no-values {:type  "uncaught_exception"
                                                               :error (.getClass e#)}))}
                 (.getMessage e#)))
       (throw e#))))

(defmacro with-timing-logs [op & body]
  `(with-timing-logs-ex ~op {:no-values false} ~@body))