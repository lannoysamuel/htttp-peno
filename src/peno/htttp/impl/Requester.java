package peno.htttp.impl;

import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;

public abstract class Requester extends Consumer {

	private final RequestProvider provider;
	private final ScheduledExecutorService executor;

	private String requestId;
	private ScheduledFuture<?> timeoutFuture;

	public Requester(Channel channel, RequestProvider provider) {
		super(channel);
		this.provider = provider;
		this.executor = Executors.newSingleThreadScheduledExecutor();
	}

	protected void request(String exchange, String topic, byte[] message)
			throws IOException {
		request(exchange, topic, message, -1);
	}

	protected void request(String exchange, String topic, byte[] message,
			int timeout) throws IOException {
		// Cancel any running requests
		cancelRequest();

		// Declare reply consumer
		String replyQueue = provider.getQueue();
		getChannel().basicConsume(replyQueue, true, this);

		// Create request
		requestId = "" + provider.nextRequestId();
		AMQP.BasicProperties props = new AMQP.BasicProperties().builder()
				.timestamp(new Date()).contentType("text/plain")
				.deliveryMode(1).expiration(timeout + "")
				.correlationId(requestId).replyTo(replyQueue).build();

		// Publish
		getChannel().basicPublish(exchange, topic, props, message);

		// Set timeout
		if (timeout > 0) {
			timeoutFuture = executor.schedule(new Runnable() {
				@Override
				public void run() {
					handleTimeout();
				}
			}, timeout, TimeUnit.MILLISECONDS);
		}
	}

	protected void cancelRequest() throws IOException {
		// Cancel timeout
		if (timeoutFuture != null) {
			timeoutFuture.cancel(false);
			timeoutFuture = null;
		}
		// Cancel consumer
		if (getConsumerTag() != null) {
			getChannel().basicCancel(getConsumerTag());
		}
	}

	@Override
	public void handleMessage(String topic, Map<String, Object> message,
			BasicProperties props) {
		if (props.getCorrelationId().equals(requestId)) {
			handleResponse(message, props);
		}
	}

	public abstract void handleResponse(Map<String, Object> message,
			BasicProperties props);

	public abstract void handleTimeout();

}