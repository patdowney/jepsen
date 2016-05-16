(ns jepsen.mongodb.util
  (:require [clojure.tools.logging :refer [debug info warn trace spy]]
            [clojure.string :as str]))

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
                       :info
                       :fail)]
     (try
       ~@body
       (catch com.mongodb.MongoNotPrimaryException e#
         (assoc ~op :type :fail, :error :not-primary))

       ; A network error is indeterminate
       (catch com.mongodb.MongoSocketReadException e#
         (assoc ~op :type error-type# :error :socket-read))

       (catch com.mongodb.MongoSocketReadTimeoutException e#
         (assoc ~op :type error-type# :error :socket-read)))))
