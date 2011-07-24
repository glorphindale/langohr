(ns langohr.basic
  (:import (com.rabbitmq.client Channel AMQP AMQP$BasicProperties AMQP$BasicProperties$Builder QueueingConsumer)))


;;
;; API
;;

(defn publish
  "Publishes a message using basic.publish AMQP method"
  [^Channel channel, ^String payload,
   {exchange         :exchange
    routing-key      :routing-key
    content-type     :content-type
    content-encoding :content-encoding
    headers          :headers
    persistent       :persistent
    priority         :priority
    correlation-id   :correlation-id
    reply-to         :reply-to
    expiration       :expiration
    message-id       :message-id
    timestamp        :timestamp
    type             :type
    user-id          :user-id
    app-id           :app-id
    cluster-id       :cluster-id}]
  (let [payload-bytes      (.getBytes payload)
        properties-builder (AMQP$BasicProperties$Builder.)
        properties         (.build (doto properties-builder
                             (.contentType     content-type)
                             (.contentEncoding content-encoding)
                             (.headers         headers)
                             (.deliveryMode    (if persistent 2 1))
                             (.priority        priority)
                             (.correlationId   correlation-id)
                             (.replyTo         reply-to)
                             (.expiration      expiration)
                             (.messageId       message-id)
                             (.timestamp       timestamp)
                             (.type            type)
                             (.userId          user-id)
                             (.appId           app-id)
                             (.clusterId       cluster-id)))]
    (.basicPublish channel exchange routing-key properties payload-bytes)))


(defn consume
  "Adds new consumer to a queue using basic.consume AMQP method"
  [^Channel channel, ^String queue, ^clojure.lang.IFn message-handler, { consumer-tag :consumer-tag auto-ack :auto-ack  }]
  (let [queueing-consumer (QueueingConsumer. channel)]
    (do
      (.basicConsume channel queue auto-ack queueing-consumer)
      (while true
        (try
          (let [delivery (.nextDelivery queueing-consumer)]
            (message-handler delivery (.getProperties delivery) (.getBody delivery)))
          (catch InterruptedException e
            nil))))))