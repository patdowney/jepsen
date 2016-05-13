(ns jepsen.mongodb.runner
  "Runs the full Mongo test suite, including a config file. Provides exit
  status reporting."
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen.mongodb [core :as m]
             [mongo :as client]
             [document-cas :as dc]
             [simple-client :as sc]]
            [jepsen.core :as jepsen]
            [aero.core :refer [read-config]]
            [jepsen.control :as control]
            [jepsen.mongodb.log-context :refer [with-logging-context]]))

(defn random-string [length]
  (let [ascii-codes (concat (range 48 58) (range 66 91) (range 97 123))]
    (apply str (repeatedly length #(char (rand-nth ascii-codes))))))

(def usage
  "Usage: java -jar jepsen.mongodb.jar path_to_options_file

Runs a Jepsen test and exits with a status code:

  0     All tests passed
  1     Some test failed
  254   Invalid arguments
  255   Internal Jepsen error

Options file must (currently) be an edn file i.e. clojure-like syntax

It will take the file in resources/defaults.edn as defaults")


(defn merge-overwrite
  [v1 v2]
  (if (and (associative? v1) (associative? v2))
    (merge-with merge-overwrite v1 v2)
    v2))

(defn -main
  [& args]
  (try
    (if-not (= 1 (count args))
      (do
        (println "No config file specified")
        (println usage)
        (System/exit 0)))

    (let [default-options (read-config "resources/defaults.edn")
          custom-options (read-config (first args))
          options (merge-with merge-overwrite default-options custom-options)]

      (with-logging-context {:run-id (random-string 10) :scenario (:scenario options)}

        (info "Test options:\n" (with-out-str (pprint options)))

        ; Run test
        (binding [control/*trace* (:trace-ssh options)]
          (let [t (jepsen/run! (sc/test options))]
            (System/exit (if (:valid? (:results t)) 0 1))))))

    (catch Throwable t
      (fatal t "Oh jeez, I'm sorry, Jepsen broke. Here's why:")
      (System/exit 255))))
