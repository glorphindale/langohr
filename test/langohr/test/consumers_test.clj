(ns langohr.test.consumers-test
  (:require [langohr.queue     :as lhq]
            [langohr.core      :as lhc]
            [langohr.channel   :as lch]
            [langohr.basic     :as lhb]
            [langohr.util      :as lhu]
            [langohr.consumers :as lhcons]
            [langohr.shutdown  :as lsh]
            [clojure.test      :refer :all])
    (:import [com.rabbitmq.client Consumer AMQP$Queue$DeclareOk]
             [java.util.concurrent TimeUnit CountDownLatch]))


;;
;; API
;;

(defonce conn (lhc/connect))


(deftest t-consume-ok-handler
  (let [channel  (lch/open conn)
        queue    (lhq/declare-server-named channel)
        latch    (CountDownLatch. 1)
        consumer (lhcons/create-default channel :handle-consume-ok-fn (fn [consumer-tag]
                                                                        (.countDown latch)))]
    (lhb/consume channel queue consumer)
    (is (.await latch 1000 TimeUnit/MILLISECONDS))))

(deftest t-basic-cancel
  (let [ch    (lch/open conn)
        q     (lhq/declare-server-named ch)
        ctag  "langohr.consumer-tag1"
        latch (CountDownLatch. 1)]
    (lhcons/subscribe ch q (fn [_ _ _])
                      :consumer-tag ctag :handle-cancel-ok (fn [_]
                                                             (.countDown latch)))
    (lhb/cancel ch ctag)
    (is (.await latch 1000 TimeUnit/MILLISECONDS))))

(deftest t-basic-cancel-with-exclusive-consumer
  (let [ch    (lch/open conn)
        q     (lhq/declare-server-named ch)
        ctag  "langohr.consumer-tag"
        latch (CountDownLatch. 1)]
    (lhcons/subscribe ch q (fn [_ _ _])
                      :consumer-tag ctag :handle-cancel-ok (fn [_]
                                                             (.countDown latch))
                      :exclusive true)
    (lhb/cancel ch ctag)
    (is (.await latch 1000 TimeUnit/MILLISECONDS))))

(deftest t-basic-cancel-with-exclusive-consumer-on-different-connection
  (let [c2    (lhc/connect)
        ch    (lch/open conn)
        ch2   (lch/open c2)
        q     (:queue (lhq/declare ch "" :exclusive false))
        ctag  "langohr.consumer-tag"]
    (lhcons/subscribe ch q (fn [_ _ _])
                      :consumer-tag ctag :exclusive true)
    (is (thrown? java.io.IOException
                 (lhb/cancel ch2 ctag)))
    (try
      (lhb/cancel ch2 ctag)
      (catch java.io.IOException e
        (is (= (.getMessage e) "Unknown consumerTag"))))))

(deftest t-consume-ok-handler-with-queueing-consumer
  (let [channel  (lch/open conn)
        queue    (:queue (lhq/declare channel))
        latch    (CountDownLatch. 1)
        consumer (lhcons/create-queueing channel :handle-consume-ok-fn (fn [consumer-tag]
                                                                         (.countDown latch)))]
    (lhb/consume channel queue consumer)
    (is (.await latch 700 TimeUnit/MILLISECONDS))))


(deftest t-cancel-ok-handler
  (let [channel  (lch/open conn)
        queue    (lhq/declare-server-named channel)
        tag      (lhu/generate-consumer-tag "t-cancel-ok-handler")
        latch    (CountDownLatch. 1)
        consumer (lhcons/create-default channel :handle-cancel-ok-fn (fn [consumer-tag]
                                                                       (.countDown latch)))
        ret-tag  (lhb/consume channel queue consumer :consumer-tag tag)]
    (is (= tag ret-tag))
    (lhb/cancel channel tag)
    (is (.await latch 2 TimeUnit/SECONDS))))


(deftest t-cancel-notification-handler
  (let [channel         (lch/open conn)
        {:keys [queue]} (lhq/declare channel "to-be-deleted")
        latch           (CountDownLatch. 1)
        consumer        (lhcons/create-default channel :handle-cancel-fn (fn [consumer_tag]
                                                                    (.countDown latch)))]
    (lhb/consume channel queue consumer :consumer-tag "will-be-cancelled-by-rmq")
    (lhq/delete channel queue)
    (is (.await latch 1 TimeUnit/SECONDS))))


(deftest t-delivery-handler
  (let [channel    (lch/open conn)
        exchange   ""
        payload    ""
        queue      (lhq/declare-server-named channel)
        n          300
        latch      (CountDownLatch. (inc n))
        consumer   (lhcons/create-default channel
                                          :handle-delivery-fn   (fn [ch metadata ^bytes payload]
                                                                  (.countDown latch))
                                          :handle-consume-ok-fn (fn [consumer-tag]
                                                                  (.countDown latch)))]
    (lhb/consume channel queue consumer)
    (.start (Thread. (fn []
                       (dotimes [i n]
                         (lhb/publish channel exchange queue payload))) "publisher"))
    (is (.await latch 700 TimeUnit/MILLISECONDS))))

(deftest t-shutdown-notification-handler
  (let [ch    (lch/open conn)
        q     (:queue (lhq/declare ch))
        latch    (CountDownLatch. 1)
        consumer (lhcons/create-default ch :handle-shutdown-signal-fn (fn [consumer_tag reason]
                                                                        (.countDown latch)))]
    (lhb/consume ch q consumer)
    (.start (Thread. (fn []
                       (Thread/sleep 200)
                       ;; bind a queue that does not exist
                       (try
                         (lhq/bind ch "ugggggh" "amq.fanout")
                         (catch Exception e
                           (comment "Do nothing"))))))
    (is (.await latch 700 TimeUnit/MILLISECONDS))))
