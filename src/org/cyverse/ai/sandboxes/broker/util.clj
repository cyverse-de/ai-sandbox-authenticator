(ns org.cyverse.ai.sandboxes.broker.util
  (:import
   [org.jboss.logging Logger]))

(defn get-logger
  "Returns the correct logger to use for the given object."
  [obj]
  (Logger/getLogger (class obj)))
