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
	 * 
	 */
	private static final String RESP_END = "\n";

	/**
	 * 
	 */
	private static final String RESP_SPLIT = " ";

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

	private static final int MIN_ALIVE_TIME = 30 * 1000;// min alive minute

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
											// message[peopleId content]
											String[] items = StringUtils.split(message, " ", 2);
											if (CHANNEL_WEIBO.equals(channel)) { // 1 identified weibo
												String weiboSenderId = items[0];
												// String weiboContent = items[2];
												pushWeibo(weiboSenderId);
											} else if (CHANNEL_WEIBO_REPLY.equals(channel)) {
												String quoteSenderId = items[0];
												// String replyContent = items[2];
												pushWeiboReply(quoteSenderId, true, null);
											} else if (CHANNEL_MESSAGE.equals(channel)) {
												String receiverId = items[0];
												pushMessage(receiverId, true, null);
											}
										} catch (Exception e) {
											log.error("onMessage, message:" + message + ",channel:" + channel, e);
										}
									}

								}, CHANNEL_WEIBO, CHANNEL_WEIBO_REPLY, CHANNEL_MESSAGE, CHANNEL_AT);
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

	private void pushWeiboReply(String quoteSenderId, boolean needIncr, Long newWeiboReplyNum) {
		Jedis jedis = pool.getResource();
		try {
			long repliedNumber = needIncr ? jedis.hincrBy(CHANNEL_WEIBO_REPLY, quoteSenderId, 1) : newWeiboReplyNum;
			push(CMD_MINE, quoteSenderId, quoteSenderId + RESP_SPLIT + CHANNEL_WEIBO_REPLY + RESP_SPLIT + repliedNumber + RESP_END, false);
		} catch (JedisConnectionException e) {
			log.error(CHANNEL_WEIBO_REPLY, e);
			jedis.disconnect();
		} finally {
			pool.returnResource(jedis);
		}

	}

	private void pushMessage(String receiverId, boolean needIncr, Long newMessageNum) {
		Jedis jedis = pool.getResource();
		try {
			long repliedNumber = needIncr ? jedis.hincrBy(CHANNEL_MESSAGE, receiverId, 1) : newMessageNum;
			push(CMD_MINE, receiverId, receiverId + RESP_SPLIT + CHANNEL_MESSAGE + RESP_SPLIT + repliedNumber + RESP_END, false);
		} catch (JedisConnectionException e) {
			log.error(CHANNEL_MESSAGE, e);
			jedis.disconnect();
		} finally {
			pool.returnResource(jedis);
		}
	}

	private void pushWeibo(String senderId) {
		push(CHANNEL_WEIBO, senderId, CHANNEL_WEIBO + RESP_SPLIT + "new" + RESP_END, true);
	}

	private void push(String cmd, String peopleId, String msg, boolean closeAfterPush) {
		List<PushExecutor> list = executorMap.get(peopleId);
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
					exe.putMsg(msg);
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
		String[] peopleIds = StringUtils.split(req.getParameter("peopleIds"), ',');
		if (ArrayUtils.isEmpty(peopleIds)) {
			return;
		}
		String cmd = req.getParameter("cmd");
		if (!(CMD_MINE.equals(cmd) || CHANNEL_WEIBO.equals(cmd))) {
			return;
		}
		long aliveTime = 0;
		String time = req.getParameter("aliveTime");
		if (time == null) {
			aliveTime = MIN_ALIVE_TIME;
		} else {
			aliveTime = Long.valueOf(time);
			if (aliveTime < 0) {
				aliveTime = MIN_ALIVE_TIME;
			} else if (aliveTime > MAX_ALIVE_TIME) {
				aliveTime = MAX_ALIVE_TIME;
			}
		}
		resp.setContentType("application/json;charset=UTF-8");
		final AsyncContext ctx = req.startAsync(req, resp);
		ctx.setTimeout(aliveTime);
		final PushExecutor exe = new PushExecutor(ctx, cmd);
		subscribePeople(peopleIds, exe);
		if (CMD_MINE.equals(cmd)) {
			pushWhenNewVisit(peopleIds);
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
				} else {
					Long newWeiboReplyNum = jedis.hincrBy(CHANNEL_WEIBO_REPLY, peopleId, 0);
					if (newWeiboReplyNum > 0) {
						pushWeiboReply(peopleId, false, newWeiboReplyNum);
					}
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
