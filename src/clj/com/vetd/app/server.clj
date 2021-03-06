(ns com.vetd.app.server
  (:require [com.vetd.app.util :as ut]
            [com.vetd.app.common :as com]
            [migratus.core :as mig]
            [com.vetd.app.env :as env]
            [com.vetd.app.db :as db]
            [com.vetd.app.links :as l]
            [com.vetd.app.hasura :as ha]
            [com.vetd.app.forward :as f]
            [clojure.core.async :as a]
            [compojure.core :as c]
            [compojure.handler :as ch]
            [compojure.route :as cr]
            [hiccup.page :as page]
            [aleph.http :as ah]
            [aleph.http.client-middleware :as ahcm]
            [manifold.stream :as ms]
            [ring.middleware.cookies :as rm-cookies]
            [ring.middleware.params :as rm-params]            
            [taoensso.timbre :as log]
            [cognitect.transit :as t]
            [clojure.java.io :as io]
            [com.vetd.app.auth :as auth]
            com.vetd.app.buyers
            com.vetd.app.vendors
            com.vetd.app.admin
            com.vetd.app.groups
            com.vetd.app.integrations.plaid)
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream])
  (:gen-class))

(defonce server (atom nil))
(defonce ws-conns (atom #{})) ;; TODO
(defonce kill-keep-alive-thread (atom false))
(defonce keep-alive-thread (atom nil))
;; cache key set on startup, unique every 5 minutes, appended to some resources
(def cache-key
  (let [now (ut/now)]
    (- now (mod now (* 1000 60 5)))))


;; Multi-Payload Requests & Msg Ids =================================================

;; In order to combat the inherent instability of websockets, we have
;; the following system in place:

;; - clients (browser-side) keep a collection of outgoing requests
;; - clients listen for acks from the server
;; - upon receiving an ack, the client removes the ack'd request from the collection
;; - clients send the entire collection of requests to the server 
;; - to protect against processing a previously processed request, each request has a unique (to the client) msg id
;; - the server keeps track of the last 30 msg ids seen from each client


(def msg-ids-by-ws-id& (atom {}))

(defn push-msg-ids [ws-id ids]
  (swap! msg-ids-by-ws-id&
         (fn [msg-ids-by-ws-id]
           (assoc msg-ids-by-ws-id
                  ws-id
                  (->> ws-id
                       msg-ids-by-ws-id
                       (concat ids)
                       ;; TODO 30 is good?????
                       (take 30))))))

(defn filter-by-msg-ids-by-ws-id& [ws-id msgs-by-id]
  (reduce dissoc msgs-by-id (@msg-ids-by-ws-id& ws-id)))

(defn process-ws-payloads
  [ws-id payloads]
  (let [r (->> payloads
               (filter-by-msg-ids-by-ws-id& ws-id)
               vals
               (sort-by :ws/ts)
               vec)]
    (push-msg-ids ws-id (keys payloads))
    r))

;; END Multi-Payload Requests & Msg Ids =============================================

(defn uri->content-type
  [uri]
  (or (-> uri
          (clojure.string/split #"\.")
          last
          ({"js" "application/javascript"
            "css" "text/css"
            "html" "text/html"
            "svg" "image/svg+xml"}))
      "text/plain"))

(defn public-resource-response
  [uri]
  (if-let [body (some-> (str "public" uri) io/resource io/input-stream)]
    {:status 200
     :headers {"Content-Type" (uri->content-type uri)}
     :body body}
    {:status 404
     :body "NOT FOUND"}))

(defmacro serve-public-resource
  [path]
  `(compojure.core/GET ~path [] (comp public-resource-response :uri)))

(defn ->transit
  [v]
  (let [baos (ByteArrayOutputStream.)
        tw (t/writer baos :json
                     {:handlers {}})]
    (t/write tw v)
    (.toByteArray baos)))



(defn respond-transit
  [data ws]
  (try
    (ms/try-put! ws
                 (String. (->transit data)) ;; TODO this can't be a byte array or something???
                 200)
    (catch Throwable e
      (com/log-error e))))

;; use muutanja??
(defn read-transit-string
  [s]
  (-> s
      .getBytes
      (ByteArrayInputStream.)
      (t/reader :json)
      t/read))

(defn start-keep-alive-thread []
  (reset! kill-keep-alive-thread false)
  (if-not @keep-alive-thread
    (do (log/info "Starting keep-alive thread...")  
        (reset! keep-alive-thread
                (future
                  (do (log/info "Started keep-alive thread.")  
                      (while (not @kill-keep-alive-thread)
                        (try
                          (Thread/sleep 30000)
                          (doseq [ws @ws-conns]
                            (respond-transit {:cmd :keep-alive
                                              :return nil
                                              :response nil}
                                             ws))
                          (catch Throwable t
                            (com/log-error t))))
                      (log/info "Stopped keep-alive thread.")
                      :done))))
    (log/info "Keep-alive thread already running.")))

(defn stop-keep-alive-thread []
  (log/info "Stopping keep-alive thread...")
  (reset! kill-keep-alive-thread true)
  (reset! keep-alive-thread nil))

(defn ws-outbound-handler
  [ws ws-id {:keys [cmd return] :as req} counter& req-ts data]
  (com/hc-send {:type "ws-outbound-handler:respond"
                :ws-id ws-id
                :cmd cmd
                :return return
                :request req
                :response data
                :response-size (-> data ->transit count)
                :resp-idx (swap! counter& inc)
                :latency-ms (- (ut/now) req-ts)})
  (respond-transit {:cmd cmd
                    :return return
                    :response data}
                   ws))

(defmethod com/handle-ws-inbound :get-cache-key
  [data ws-id resp-fn]
  {:cache-key cache-key})

(defn ws-inbound-handler*
  [ws ws-id {:keys [cmd return session-token] :as data}]
  (try
    (com/hc-send {:type "ws-inbound-handler:receive"
                  :ws-id ws-id
                  :cmd cmd
                  :return return
                  :request data})
    (let [{session-id :id user-id :user-id}
          (when session-token
            (auth/select-session-by-token session-token))
          resp-fn (partial #'ws-outbound-handler
                           ws
                           ws-id
                           data
                           (atom 0)
                           (ut/now))
          resp (binding [com/*session-id* session-id
                         com/*user-id* user-id]
                 (com/handle-ws-inbound data ws-id resp-fn))]
      (when (and return resp)
        (resp-fn resp)))
    (catch Exception e
      (com/log-error e))))

(defn ws-inbound-handler
  [ws ws-id data]
  (try
    (let [{:keys [payloads] :as data'} (read-transit-string data)
          payloads' (process-ws-payloads ws-id payloads)]
      (#'ws-outbound-handler ws
                             ws-id
                             {:cmd nil
                              :return :ws/ack}
                             (atom 0)
                             (ut/now)
                             {:msg-ids (distinct (@msg-ids-by-ws-id& ws-id))})
      (doseq [p payloads']
        (ws-inbound-handler* ws ws-id p)))
    (catch Throwable e
      (com/log-error e))))

(defn ws-on-closed
  [ws ws-id & args]
  (doseq [f (-> (@com/ws-on-close-fns& ws-id) vals)]
    (try
      (f)
      (catch Throwable t
        (com/log-error t))))
  (swap! ws-conns disj ws)
  (swap! msg-ids-by-ws-id& dissoc ws-id)
  (swap! com/ws-on-close-fns& dissoc ws-id)
  true)

(defn ws-handler
  [req]
  (let [ws @(ah/websocket-connection req
                                     {:max-frame-payload (* 32 65536)
                                      :max-frame-size (* 32 65536)})
        ws-id (str (gensym "ws"))]
    (swap! ws-conns conj ws)
    (ms/on-closed ws (partial ws-on-closed ws ws-id))
    (ms/consume (partial ws-inbound-handler
                         ws
                         ws-id)
                ws)
    "ws"))

(defn admin-session? [cookies]
  (-> "admin-token"
      cookies
      :value
      auth/admin-session?))

(defn control-cache
  "Forces cache refresh per deploy"
  [uri]
  (str uri "?" cache-key))

(defn app-html
  [cookies]
  (page/html5
   [:head
    [:title (str "Vetd | "
                 (if (admin-session? cookies)
                   "ADMIN"
                   "Business Software Buying Platform"))]
    [:meta {:http-equiv "Content-Type"
            :content "text/html; charset=UTF-8"}]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1"}]
    ;; favicon etc
    [:link {:rel "apple-touch-icon" :sizes "180x180"
            :href "/apple-touch-icon.png"}]
    [:link {:rel "icon" :type "image/png" :sizes "32x32"
            :href "/favicon-32x32.png"}]
    [:link {:rel "icon" :type "image/png" :sizes "16x16"
            :href "/favicon-16x16.png"}]
    [:link {:rel "manifest" :href "/site.webmanifest"}]
    [:link {:rel "mask-icon" :color "#4ec2c4"
            :href "/safari-pinned-tab.svg"}]
    [:meta {:name "apple-mobile-web-app-title" :content "Vetd"}]
    [:meta {:name "application-name" :content "Vetd"}]
    [:meta {:name "msapplication-TileColor" :content "#00aba9"}]
    [:meta {:name "theme-color" :content "#ffffff"}]
    (page/include-css (control-cache "/assets/css/semantic-ui.css"))
    (page/include-css "/assets/lib/toastr/toastr.min.css")
    (page/include-css (control-cache "/assets/css/app.css"))
    (when (admin-session? cookies)
      (page/include-css (control-cache "/assets/css/admin.css")))]
   [:body
    [:div {:id "app"}
     ;; loading spinner
     [:div {:class "spinner"
            :style "margin: 175px auto;"}
      [:i] [:i] [:i]]]
    [:div.window-size-message "Vetd is optimized for desktop use with a window width of 1200px or more."]
    ;; load Segment's "Analytics.js"
    [:script {:type "text/javascript"}
     "!function(){var analytics=window.analytics=window.analytics||[];if(!analytics.initialize)if(analytics.invoked)window.console&&console.error&&console.error(\"Segment snippet included twice.\");else{analytics.invoked=!0;analytics.methods=[\"trackSubmit\",\"trackClick\",\"trackLink\",\"trackForm\",\"pageview\",\"identify\",\"reset\",\"group\",\"track\",\"ready\",\"alias\",\"debug\",\"page\",\"once\",\"off\",\"on\"];analytics.factory=function(t){return function(){var e=Array.prototype.slice.call(arguments);e.unshift(t);analytics.push(e);return analytics}};for(var t=0;t<analytics.methods.length;t++){var e=analytics.methods[t];analytics[e]=analytics.factory(e)}analytics.load=function(t,e){var n=document.createElement(\"script\");n.type=\"text/javascript\";n.async=!0;n.src=\"https://cdn.segment.com/analytics.js/v1/\"+t+\"/analytics.min.js\";var a=document.getElementsByTagName(\"script\")[0];a.parentNode.insertBefore(n,a);analytics._loadOptions=e};analytics.SNIPPET_VERSION=\"4.1.0\";}}();"]
    (page/include-js "/assets/js/plaid/link-initialize.js")
    (page/include-js
     (control-cache
      (if (admin-session? cookies)
        "/js/full.js"
        "/js/app.js")))
    (page/include-js "/assets/js/confetti.js")
    ;; load Chatra
    (when-not (admin-session? cookies)
      [:script {:type "text/javascript"}
       "(function(d, w, c) {
         w.ChatraID = 'YwgMHTcCTsLojxazy';
         var s = d.createElement('script');
         w[c] = w[c] || function() {
           (w[c].q = w[c].q || []).push(arguments);
         };
         s.async = true;
         s.src = 'https://call.chatra.io/chatra.js';
         if (d.head) d.head.appendChild(s);
       })(document, window, 'Chatra');"])
    ]))

(def app
  (-> (c/routes
       (c/GET "/l/:k" [k]
              (fn [{:keys [cookies]}]
                (l/do-action-by-key k)
                (app-html cookies)))
       (c/POST "/forward" resp
               (->> (ahcm/coerce-json-body {:coerce :always} resp true false)
                    :body
                    :message
                    f/forward)
               ;; TODO send something back
               {:status 200
                :headers {"content-type" "application/json"}
                :body (ahcm/json-encode {:success true})})
       (c/GET "/ws" [] #'ws-handler)
       (cr/resources "/assets" {:root "public/assets"})
       (c/GET "/assets*" [] cr/not-found)
       (serve-public-resource "/apple-touch-icon.png")
       (serve-public-resource "/favicon-32x32.png")
       (serve-public-resource "/favicon-16x16.png")
       (serve-public-resource "/site.webmanifest")
       (serve-public-resource "/safari-pinned-tab.svg")
       (c/GET "/js/app.js" [] (fn [{:keys [uri cookies]}]
                                (if env/prod?
                                  (public-resource-response uri)
                                  (public-resource-response "/js/full.js"))))
       (c/GET "/js/full.js" [] (fn [{:keys [uri cookies]}]
                                 (if (admin-session? cookies)
                                   (public-resource-response uri)
                                   "")))
       (serve-public-resource "/js*")
       ;; when updating "*" route, also update "/l/:k" route
       (c/GET "*" [] (fn [{:keys [cookies]}]
                       (app-html cookies))))
      rm-cookies/wrap-cookies
      rm-params/wrap-params))

(defn start-server []
  (log/info "starting http server...")
  (try
    (if-not @server
      (do (start-keep-alive-thread)
          (reset! server
                  (ah/start-server #'app {:port 5080}))
          (log/info "started http server on port 5080"))
      (log/info "server already running"))
    (catch Exception e
      (com/log-error e "EXCEPTION while trying to start http server"))))

(defn stop-server []
  (log/info "stopping http server...")
  (try
    (stop-keep-alive-thread)
    (com/force-all-ws-on-close-fns)
    (reset! msg-ids-by-ws-id& {})
    (ha/send-terminate)
    (if-let [svr @server]
      (do
        (.close svr)
        (reset! server nil)
        (log/info "executed close for http server on port 5080")
        svr)
      (log/info "There was no server to stop."))
    (catch Exception e
      (com/log-error e "EXCEPTION while trying to stop http server"))))

#_ (stop-server)

#_(start-server)
