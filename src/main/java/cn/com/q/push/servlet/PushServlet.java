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
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.util.CollectionUtils;

import redis.clients.jedis.Jedis;

/**
 * @author seanlinwang at gmail dot com
 * @date Apr 26, 2011
 * 
 */
public class PushServlet extends HttpServlet {
	private static final Logger log = Logger.getLogger(PushServlet.class);

	private static final long serialVersionUID = 2917628173593609625L;

	private static final int MAX_ALIVE_TIME = 5 * 60 * 1000;// alive five minute

	private static final int MIN_ALIVE_TIME = 30 * 1000;// min alive minute

	private Jedis subJedis = null;

	private Jedis cacheJedis = null;

	@Override
	public void init() throws ServletException {
		ServletConfig config = this.getServletConfig();
		String subHost = config.getInitParameter("subHost");
		int subPort = Integer.valueOf(config.getInitParameter("subPort"));
		int subTimeout = Integer.valueOf(config.getInitParameter("subTimeout"));
		subJedis = new Jedis(subHost, subPort, subTimeout);
		try {
			subJedis.connect();
		} catch (IOException e) {
			throw new ServletException(e);
		}
		final AtomicInteger tryTimes = new AtomicInteger();
		new Thread(new Runnable() {

			@Override
			public void run() {
				while (true) {
					try {
						int tryTime = tryTimes.incrementAndGet();
						if (tryTime != 1) {
							log.error("subscribe redis fail, try times:" + tryTime);
							if (tryTime == 4) {
								break;
							}
						}

						subJedis.subscribe(new JedisPubSubWraper() { // subscribe message and push message content to http clients

									@Override
									public void onMessage(String channel, String message) {
										try {
											// message[peopleId content]
											String[] items = StringUtils.split(message, " ", 2);
											if ("weibo".equals(channel)) { // 1 identified weibo
												String weiboSenderId = items[0];
												// String weiboContent = items[2];
												pushWeibo(weiboSenderId);
											} else if ("weiboReply".equals(channel)) {
												String quoteSenderId = items[0];
												// String replyContent = items[2];
												pushWeiboReply(quoteSenderId);
											}
										} catch (Exception e) {
											log.error("onMessage, message:" + message + ",channel:" + channel, e);
										}
									}

								}, "weibo", "weiboReply", "message", "messageReply", "at");
					} catch (Exception e) {
						log.error("subscribe fail", e);
					}
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						log.error("", e);
					}
				}
			}
		}).start();

		String cacheHost = config.getInitParameter("cacheHost");
		int cachePort = Integer.valueOf(config.getInitParameter("cachePort"));
		int cacheTimeout = Integer.valueOf(config.getInitParameter("cacheTimeout"));
		this.cacheJedis = new Jedis(cacheHost, cachePort, cacheTimeout);
	}

	private void pushWeiboReply(String quoteSenderId) {
		int repliedNumber = cacheJedis.incr("weiboReply " + quoteSenderId);
		push("mine", quoteSenderId, "weiboReply new " + repliedNumber + "\n", false);

	}

	private void pushWeibo(String senderId) {
		push("weibo", senderId, "weibo new\n", true);
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

	private ConcurrentHashMap<String, List<PushExecutor>> executorMap = new ConcurrentHashMap<String, List<PushExecutor>>();

	@Override
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
		doPost(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String[] peopleIds = StringUtils.split(req.getParameter("peopleIds"), ',');
		String cmd = req.getParameter("cmd");
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
		// exe.setAliveTime(aliveTime);
		ctx.addListener(new AsyncListener() {

			@Override
			public void onComplete(AsyncEvent event) throws IOException {
				log.debug("onComplete");
			}

			@Override
			public void onTimeout(AsyncEvent event) throws IOException {
				log.debug("onTimeout");
				exe.end();
			}

			@Override
			public void onError(AsyncEvent event) throws IOException {
				log.debug("onError");
				exe.end();
			}

			@Override
			public void onStartAsync(AsyncEvent event) throws IOException {
				log.debug("onStartAsync");
			}
		});
		subscribePeople(peopleIds, exe);
		new Thread(exe).start();
		log.debug("start executor:" + exe);
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
