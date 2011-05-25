/**
 * 
 */
package cn.com.q.push.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.pool.impl.GenericObjectPool.Config;
import org.apache.log4j.Logger;
import org.springframework.util.CollectionUtils;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * @author seanlinwang at gmail dot com
 * @date Apr 26, 2011
 * 
 */
public class PushServlet extends HttpServlet {

	/**
	 * group new weibo channel
	 */
	private static final String CHANNEL_GROUP = "group";

	/**
	 * 
	 */
	private static final String CHANNEL_WEIBO = "weibo";

	/**
	 * 
	 */
	private static final String CHANNEL_WEIBO_REPLY = "weiboReply";

	/**
	 * 
	 */
	private static final String CHANNEL_MESSAGE = "message";

	/**
	 * 
	 */
	private static final String CHANNEL_AT = "at";

	/**
	 * 
	 */
	private static final String CMD_MINE = "mine";

	private static final Logger log = Logger.getLogger(PushServlet.class);

	private static final long serialVersionUID = 2917628173593609625L;

	private static final int MAX_ALIVE_TIME = 5 * 60 * 1000;// alive five minute

	private static final int MIN_ALIVE_TIME = 1 * 1000;// min alive minute

	private JedisPool pool = null;

	private String cacheHost;

	private int cachePort;

	private int cacheTimeout;

	private ConcurrentHashMap<String, List<PushExecutor>> executorMap = new ConcurrentHashMap<String, List<PushExecutor>>();

	@Override
	public void init() throws ServletException {
		ServletConfig config = this.getServletConfig();
		final String subHost = config.getInitParameter("subHost");
		final int subPort = Integer.valueOf(config.getInitParameter("subPort"));
		final int subTimeout = Integer.valueOf(config.getInitParameter("subTimeout"));

		final AtomicInteger tryTimes = new AtomicInteger();
		new Thread(new Runnable() {

			@Override
			public void run() {
				while (true) {
					Jedis subJedis = null;
					try {
						int tryTime = tryTimes.incrementAndGet();
						if (tryTime != 1) {
							log.error("subscribe redis fail, try times:" + tryTime);
							if (tryTime == 4) {
								break;
							}
						}
						subJedis = createSubJedis(subHost, subPort, subTimeout);
						subJedis.subscribe(new JedisPubSubWraper() { // subscribe message and push message content to http clients

									@Override
									public void onMessage(String channel, String message) {
										try {
											// message[Id/Ids content]
											String[] items = StringUtils.split(message, " ", 2);
											if (CHANNEL_GROUP.equals(channel)) {
												String groupId = items[0];
												pushGroupWeibo(groupId);
											} else if (CHANNEL_WEIBO.equals(channel)) { // 1 identified weibo
												String weiboSenderId = items[0];
												// String weiboContent = items[2];
												pushWeibo(weiboSenderId);
											} else if (CHANNEL_WEIBO_REPLY.equals(channel)) {
												String quoteSenderId = items[0];
												// String replyContent = items[2];
												pushWeiboReply(quoteSenderId, true, null);
											} else if (CHANNEL_MESSAGE.equals(channel)) {
												String receiverIds = items[0];
												pushMessage(receiverIds, true, null);
											}
										} catch (Exception e) {
											log.error("onMessage, message:" + message + ",channel:" + channel, e);
										}
									}

								}, CHANNEL_GROUP, CHANNEL_WEIBO, CHANNEL_WEIBO_REPLY, CHANNEL_MESSAGE, CHANNEL_AT);
					} catch (JedisConnectionException e) {
						log.error("SubJedis will colsed and renew:", e);
						if (null != subJedis) {
							subJedis.disconnect();
						}
					} catch (Exception e) {
						log.error("Other subscribe exception:", e);
					}
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						log.error("Sleep interrupted:", e);
					}
				}
			}
		}).start();

		cacheHost = config.getInitParameter("cacheHost");
		cachePort = Integer.valueOf(config.getInitParameter("cachePort"));
		cacheTimeout = Integer.valueOf(config.getInitParameter("cacheTimeout"));
		this.pool = new JedisPool(new Config(), cacheHost, cachePort, cacheTimeout);
	}

	protected Jedis createSubJedis(String subHost, int subPort, int subTimeout) {
		return new Jedis(subHost, subPort, subTimeout);
	}

	private static class PushResult {
		String id;

		String type;

		String content;

		PushResult(String id, String type, String content) {
			this.id = id;
			this.type = type;
			this.content = content;
		}

		@Override
		public String toString() {
			return "{id=\"" + id + "\", type=\"" + type + "\", content=\"" + content + "\"}";
		}

	}

	private void pushWeiboReply(String quoteSenderId, boolean needIncr, Long newWeiboReplyNum) {
		Jedis jedis = pool.getResource();
		try {
			long repliedNumber = needIncr ? jedis.hincrBy(CHANNEL_WEIBO_REPLY, quoteSenderId, 1) : newWeiboReplyNum;
			PushResult pr = new PushResult(quoteSenderId, CHANNEL_WEIBO_REPLY, "" + repliedNumber);
			push(CMD_MINE, quoteSenderId, pr, false);
		} catch (JedisConnectionException e) {
			log.error(CHANNEL_WEIBO_REPLY, e);
			jedis.disconnect();
		} finally {
			pool.returnResource(jedis);
		}

	}

	private void pushMessage(String receiverIds, boolean needIncr, Long newMessageNum) {
		Jedis jedis = pool.getResource();
		String[] idArray = StringUtils.split(receiverIds, ',');
		try {
			for (String receiverId : idArray) {
				long repliedNumber = needIncr ? jedis.hincrBy(CHANNEL_MESSAGE, receiverId, 1) : newMessageNum;
				PushResult pr = new PushResult(receiverId, CHANNEL_MESSAGE, "" + repliedNumber);
				push(CMD_MINE, receiverId, pr, false);
			}
		} catch (JedisConnectionException e) {
			log.error(CHANNEL_MESSAGE, e);
			jedis.disconnect();
		} finally {
			pool.returnResource(jedis);
		}
	}

	private void pushWeibo(String senderId) {
		PushResult pr = new PushResult(senderId, CHANNEL_WEIBO, "new");
		push(CHANNEL_WEIBO, senderId, pr, true);
	}

	private void pushGroupWeibo(String groupId) {
		PushResult pr = new PushResult(groupId, CHANNEL_GROUP, "new");
		push(CHANNEL_GROUP, groupId, pr, true);
	}

	private void push(String cmd, String id, PushResult pr, boolean closeAfterPush) {
		List<PushExecutor> list = executorMap.get(id);
		if (CollectionUtils.isEmpty(list)) {
			return;
		}
		synchronized (list) {
			for (Iterator<PushExecutor> iter = list.iterator(); iter.hasNext();) {
				PushExecutor exe = iter.next();
				if (exe.isEnded()) {
					iter.remove();
					if (log.isDebugEnabled()) {
						log.debug("remove executor:" + exe);
					}
					continue;
				}
				if (exe.getCmd().equals(cmd)) {
					exe.putMsg(pr.toString() + "\n");
					if (closeAfterPush) {
						exe.end(); // ended immediately after push
					}
				}
			}
		}
	}

	@Override
	public void destroy() {
		pool.destroy();
	}

	@Override
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
		doPost(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String cmd = req.getParameter("cmd");
		if (!(CMD_MINE.equals(cmd) || CHANNEL_WEIBO.equals(cmd) || CHANNEL_GROUP.equals(cmd))) {
			return;
		}
		String[] ids = StringUtils.split(req.getParameter("ids"), ',');
		if (ArrayUtils.isEmpty(ids)) {
			return;
		}
		String time = req.getParameter("aliveTime");
		long aliveTime = Long.valueOf(time); // use min alive time by default
		if (aliveTime < MIN_ALIVE_TIME) { // alive time can't be less than min alive time
			aliveTime = MIN_ALIVE_TIME;
		} else if (aliveTime > MAX_ALIVE_TIME) { // alive time can't be greater than max alive time
			aliveTime = MAX_ALIVE_TIME;
		}
		resp.setContentType("application/json;charset=UTF-8");
		final AsyncContext ctx = req.startAsync(req, resp);
		ctx.setTimeout(aliveTime);
		final PushExecutor exe = new PushExecutor(ctx, cmd);
		subscribePeople(ids, exe);
		if (CMD_MINE.equals(cmd)) {
			pushWhenNewVisit(ids);
		}
		new Thread(exe).start();
		log.debug("start executor:" + exe);
	}

	private void pushWhenNewVisit(String[] peopleIds) {
		Jedis jedis = pool.getResource();
		try {
			for (String peopleId : peopleIds) {
				Long newMessageNum = jedis.hincrBy(CHANNEL_MESSAGE, peopleId, 0);
				if (newMessageNum > 0) {
					pushMessage(peopleId, false, newMessageNum);
				}
				Long newWeiboReplyNum = jedis.hincrBy(CHANNEL_WEIBO_REPLY, peopleId, 0);
				if (newWeiboReplyNum > 0) {
					pushWeiboReply(peopleId, false, newWeiboReplyNum);
				}
			}
		} catch (JedisConnectionException e) {
			log.error("pushWhenNewVisit", e);
			jedis.disconnect();
		} finally {
			pool.returnResource(jedis);
		}
	}

	/**
	 * PushExecutor subscribe peoples' messages by peopleIds.
	 * 
	 * If message arrived, PushExecutor witch subscribed corresponding people's message, will be pushed.
	 * 
	 * @param peopleIds
	 * @param pushExecutor
	 */
	private void subscribePeople(String[] peopleIds, PushExecutor pushExecutor) {
		for (String peopleId : peopleIds) {
			List<PushExecutor> list = executorMap.get(peopleId);
			if (list == null) {
				synchronized (executorMap) {
					if (null == executorMap.get(peopleId)) { // double check
						list = new ArrayList<PushExecutor>();
						executorMap.put(peopleId, list);
					}
				}
			}
			synchronized (list) {
				list.add(pushExecutor);
			}
		}
	}

}
