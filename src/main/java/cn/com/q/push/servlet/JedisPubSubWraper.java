/**
 * 
 */
package cn.com.q.push.servlet;

import org.apache.log4j.Logger;

import redis.clients.jedis.JedisPubSub;

/**
 * @author seanlinwang at gmail dot com
 * @date May 4, 2011
 * 
 */
public class JedisPubSubWraper extends JedisPubSub {
	private static final Logger log = Logger.getLogger(JedisPubSubWraper.class);

	@Override
	public void onMessage(String channel, String message) {
		if (log.isDebugEnabled()) {
			log.debug("onMessage, message:" + message + ",channel:" + channel);
		}
	}

	@Override
	public void onPMessage(String pattern, String channel, String message) {
		if (log.isDebugEnabled()) {
			log.debug("onPMessage, message:" + message + ",pattern:" + pattern + ",channel:" + channel);
		}
	}

	@Override
	public void onSubscribe(String channel, int subscribedChannels) {
		if (log.isDebugEnabled()) {
			log.debug("onSubscribe, channel:" + channel + ",subscribedChannels:" + subscribedChannels);
		}
	}

	@Override
	public void onUnsubscribe(String channel, int subscribedChannels) {
		if (log.isDebugEnabled()) {
			log.debug("onUnsubscribe, channel:" + channel + ",subscribedChannels:" + subscribedChannels);
		}
	}

	@Override
	public void onPUnsubscribe(String pattern, int subscribedChannels) {
		if (log.isDebugEnabled()) {
			log.debug("onPUnsubscribe, pattern:" + pattern + ",subscribedChannels:" + subscribedChannels);
		}
	}

	@Override
	public void onPSubscribe(String pattern, int subscribedChannels) {
		if (log.isDebugEnabled()) {
			log.debug("onPSubscribe:" + pattern + ",subscribedChannels:" + subscribedChannels);
		}

	}

}
