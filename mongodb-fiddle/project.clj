(defproject jepsen.mongodb-fiddle "0.2.0-SNAPSHOT"
  :description "Jepsen MongoDB tests - fiddling with options"
  :url "https://github.com/aphyr/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/tools.logging "0.3.1"]
                 [log4j/log4j "1.2.17"]
                 [clj-logging-config "1.9.12"]
                 [jepsen "0.1.1-SNAPSHOT"]
                 [org.mongodb/mongodb-driver "3.2.2"]]
  :main jepsen.mongodb.runner
  :aot [jepsen.mongodb.runner
        clojure.tools.logging.impl])
