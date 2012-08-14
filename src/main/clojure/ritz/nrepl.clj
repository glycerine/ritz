(ns ritz.nrepl
  "nrepl support for ritz.

nREPL has a concept of session, that maintains state based on a session id
that is passed back and forth from client and server. We use this session to
store some extra info. We also unify the session id across the jpda and user
processes."
  (:use
   [clojure.stacktrace :only [print-cause-trace]]
   [clojure.tools.nrepl.server :only [start-server unknown-op]]
   [clojure.tools.nrepl.middleware.interruptible-eval
    :only [interruptible-eval]]
   [clojure.tools.nrepl.middleware.pr-values :only [pr-values]]
   [clojure.tools.nrepl.middleware.session :only [add-stdin session]]
   [clojure.tools.nrepl.misc :only [response-for returning]]
   [leiningen.core.eval :only [eval-in-project]]
   [ritz.connection
    :only [bindings bindings-merge! connection-close default-connection]]
   [ritz.exception-filters
    :only [exception-filters-set!
           read-exception-filters default-exception-filters]]
   [ritz.jpda.debug :only [add-exception-event-request]]
   [ritz.jpda.jdi :only [connector connector-args invoke-single-threaded]]
   [ritz.jpda.jdi-clj :only [control-eval]]
   [ritz.jpda.jdi-vm
    :only [acquire-thread launch-vm start-control-thread-body vm-resume]]
   [ritz.logging :only [set-level trace]]
   [ritz.nrepl.connections
    :only [add-pending-connection connection-for-session
           promote-pending-connection rename-connection]]
   [ritz.nrepl.debug-eval :only [debug-eval]]
   [ritz.nrepl.exec :only [read-msg]]
   [ritz.nrepl.rexec :only [rexec]]
   [ritz.nrepl.simple-eval :only [simple-eval]])
  (:require
   [clojure.java.io :as io]
   [clojure.tools.nrepl.transport :as transport]
   [ritz.jpda.jdi-clj :as jdi-clj]
   [ritz.nrepl.pr-values :as pr-values]))

;; (set-level :trace)

(defonce vm (atom nil))

(defn set-vm [vm]
  (reset! ritz.nrepl/vm vm))

(defn make-connection
  "Return a new connection map, saving the message's transport for later
reference."
  [msg]
  (let [connection (->
                    default-connection
                    (assoc :vm-context @vm :type :nrepl)
                    (merge (select-keys msg [:transport])))]
    (exception-filters-set!
     connection (or (read-exception-filters) default-exception-filters))
    connection))

(defmethod connection-close :nrepl
  [connection]
  (.close (:transport connection)))

;;; # nREPL handler and middleware

;;; ## Message log
(defn log-message
  [handler]
  (fn [{:keys [id session] :as msg}]
    (trace "Message %s" msg)
    (handler msg)))

;;; ## Connection middleware

;;; This looks up a connection map based on session id. If the session id is not
;;; set yet, then a new connection is made and stored in the pending-ids map, so
;;; that on reply the link from session to connection can be established. Since
;;; this expects the session id, it has to before any session middleware in the
;;; handler.
(defn- add-message-to-connection
  [connection msg]
  (assoc connection :msg (select-keys msg [:id :op])))

(defn connection
  [handler]
  (fn [{:keys [id session] :as msg}]
    (if session
      (handler
       (assoc msg
         ::connection
         (add-message-to-connection (connection-for-session session) msg)))
      (let [connection (make-connection msg)]
        (add-pending-connection id connection)
        (handler (assoc msg ::connection
                        (add-message-to-connection connection msg)))))))

;;; ## `eval` in jpda process
;;; This lets us eval in the jpda process, just as we would normally do in the
;;; user process
(defn jpda-eval-handler
  []
  (-> unknown-op simple-eval pr-values))

(defn jpda-eval-middleware
  "Handler for jpda actions"
  []
  (let [sub-handler (jpda-eval-handler)]
    (fn [handler]
      (fn [{:keys [op transport] :as msg}]
        (if (= op "jpda-eval")
          (do
            (trace "jpda-eval-handler %s" msg)
            (sub-handler (assoc msg :op "eval")))
          (handler msg))))))

;;; ## `eval`, etc, in user process
;;; We forward the message to the user process. The reply pump
;;; will pump all replies back to the client.
(defn execute-eval
  "Execute a jpda action"
  [host port {:keys [op transport] :as msg}]
  (trace "execute-jpda %s" op)
  (let [connection (::connection msg)]
    (rexec (:vm-context connection) msg)))

(defn return-execute-eval
  [host port {:keys [id op transport] :as msg}]
  (let [value (execute-eval host port msg)]
    (trace "return-execute-eval %s" value)
    value))

(defn rexec-handler
  "Handler for jpda actions"
  [host port]
  (fn [handler]
    (fn [{:keys [op transport] :as msg}]
      (if (#{"eval" "clone" "stdin" "interrupt"} op)
        (return-execute-eval host port msg)
        (handler msg)))))

;;; ## The overall nREPL handler for the jpda process
(defn debug-handler
  "nrepl handler with debug support"
  [host port]
  (let [rexec (rexec-handler host port)
        ;; jpda (jpda-middleware host port)
        jpda-eval (jpda-eval-middleware)
        pr-values (pr-values/pr-values #{"jpda"})]
    (-> unknown-op rexec jpda-eval debug-eval ;; session ;; add-stdin session
        pr-values connection log-message)))

;;; # Reply pump
;;; The reply pump takes all nREPL replies sent from the user process, and
;;; forwards them to the client.
(defn process-reply
  "Process a reply. The reply is expected to have a session id. If this is the
first reply for a session, the connection is looked up in the pending-ids map
based on the message id and added to the connections map. Otherwise the
connection is found in the connections map based on the session id."
  [{:keys [id new-session session status] :as msg}]
  (trace "Read reply %s" msg)
  (assert session)
  (let [connection (or (connection-for-session session)
                       (promote-pending-connection id session))]
    (assert connection)
    (when new-session
      (trace "New session %s" new-session)
      (rename-connection connection session new-session)
      ;; a refactored create-session could simplify this
      (let [s @(#'clojure.tools.nrepl.middleware.session/create-session
                (:transport connection)
                (bindings connection))
            out (#'clojure.tools.nrepl.middleware.session/session-out
                 :out new-session (:transport connection))
            err (#'clojure.tools.nrepl.middleware.session/session-out
                 :err new-session (:transport connection))]
        (trace "jpda session %s" s)
        (bindings-merge! connection s {#'*out* out #'*err* err})))
    (let [transport (:transport connection)]
      (assert transport)
      (transport/send transport msg)))
  (trace "Reply forwarded"))

(defn reply-pump
  [vm thread]
  (loop []
    (try
      (process-reply
       (jdi-clj/eval
        vm thread invoke-single-threaded
        `(read-msg)))
      (catch Exception e
        (trace "Unexpected exception in reply-pump %s" e)
        (clojure.stacktrace/print-cause-trace e)))
    (recur)))

(defn start-reply-pump
  [server vm]
  (let [thread (:msg-pump-thread vm)]
    (assert thread)
    (jdi-clj/eval vm thread invoke-single-threaded `(require 'ritz.nrepl.exec))
    (doto (Thread. ^clojure.lang.IFn (partial reply-pump vm thread))
      (.setName "Ritz-reply-msg-pump")
      (.setDaemon false)
      (.start))
    (trace "Reply pump started")))

;;; # Server set-up
(defn start-remote-thread
  "Start a remote thread in the specified vm context, using thread-name to
generate a name for the thread."
  [vm thread-name]
  (let [msg-thread-name (name (gensym thread-name))]
    (acquire-thread
     vm msg-thread-name
     (fn [context thread-name]
       (control-eval
        context (start-control-thread-body msg-thread-name))))))

(defn start-jpda-server
  [host port ack-port repl-port-path classpath]
  (let [server (start-server
                :bind "localhost" :port 0 :ack-port ack-port
                :handler (debug-handler host port))
        vm (launch-vm classpath `@(promise))
        msg-thread (start-remote-thread vm "msg-pump")
        vm (assoc vm :msg-pump-thread msg-thread)
        _ (set-vm vm)
        port (-> server deref ^java.net.ServerSocket (:ss) .getLocalPort)]
    (add-exception-event-request vm)
    (vm-resume vm)
    (start-reply-pump server vm)
    (println "nREPL server started on port" port)
    (spit repl-port-path port)))
