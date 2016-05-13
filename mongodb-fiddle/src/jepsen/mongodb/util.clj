(ns jepsen.mongodb.util
  (:require [clojure.tools.logging :refer [debug info warn trace spy]]
            [clojure.string :as str]))

(defn replace-all [str replacement-map]
  (info "replacing with" replacement-map)
  (reduce (fn [str [k v]]
            (str/replace str (re-pattern k) v))
          str
          replacement-map))