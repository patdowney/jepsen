(ns jepsen.mongodb.runner
  "Runs the full Mongo test suite, including a config file. Provides exit
  status reporting."
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :refer :all]
            [clj-logging-config.log4j :as log4j]
            [clojure.string :as str]
            [jepsen.mongodb [core :as m]
             [mongo :as client]
             [document-cas :as dc]
             [simple-client :as sc]]
            [jepsen.core :as jepsen]
            [immuconf.config :as config]
            [jepsen.control :as control]))


(def usage
  "Usage: java -jar jepsen.mongodb.jar path_to_options_file

Runs a Jepsen test and exits with a status code:

  0     All tests passed
  1     Some test failed
  254   Invalid arguments
  255   Internal Jepsen error

Options file must (currently) be an edn file i.e. clojure-like syntax

It will take the file in resources/defaults.edn as defaults")

(defn log-config! [options]
  (log4j/set-logger! "jepsen" :level (:loglevel options) :pattern (:logpattern options)))

(defn -main
  [& args]
  (try
    (if-not (= 1 (count args))
      (do
        (println "No config file specified")
        (println usage)
        (System/exit 0)))

    (let [options (config/load "resources/defaults.edn" (first args))]

      (log-config! options)

      (info "Test options:\n" (with-out-str (pprint options)))

      ; Run test
      (binding [control/*trace* (:trace-ssh options)]
        (let [t (jepsen/run! (sc/test options))]
          (System/exit (if (:valid? (:results t)) 0 1)))))

    (catch Throwable t
      (fatal t "Oh jeez, I'm sorry, Jepsen broke. Here's why:")
      (System/exit 255))))
