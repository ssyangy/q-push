/**
 * 
 */
package cn.com.q.push.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
import redis.clients.jedis.JedisPubSub;

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

	private static class PushExecutor implements Runnable {

		private boolean closed = false;

		private AsyncContext ctx;

		private static final int MAX_LINE_POOL_SIZE = 1024 * 10;

		private static final int POLL_TIMEOUT = 1000;

		private BlockingQueue<Object> msgPool = new ArrayBlockingQueue<Object>(MAX_LINE_POOL_SIZE);

		private final Object eof = new Object();

		public void putMsg(Object msg) throws InterruptedException {
			msgPool.put(msg);
		}

		public boolean isEnded() {
			return this.closed == true;
		}

		public void end() throws InterruptedException {
			putMsg(eof);
		}

		public PushExecutor(AsyncContext ctx) {
			this.ctx = ctx;
		}

		public void run() {
			long startTime = System.currentTimeMillis();
			PrintWriter out = null;
			try {
				out = ctx.getResponse().getWriter();
				Object msg = null;
				while (true) {
					if (System.currentTimeMillis() - startTime > MAX_ALIVE_TIME) {
						break;
					}
					try {
						msg = (Object) msgPool.poll(POLL_TIMEOUT, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						log.error("", e);
						continue;
					}
					if (msg == eof) {
						break;
					}
					if (msg != null) {
						log.debug("write:" + msg);
						out.write((String) msg);
						out.flush();
					}
				}
			} catch (Exception e) {
				log.debug("out closed", e);
			} finally {
				try {
					out.close();
				} catch (Exception e) {
				}
				try {
					ctx.complete();
				} catch (Exception e) {
				}
			}
			this.closed = true;
			this.msgPool = null;
		}
	}

	private Jedis jedis = null;

	@Override
	public void init() throws ServletException {
		ServletConfig config = this.getServletConfig();
		String cacheHost = config.getInitParameter("cacheHost");
		int cachePort = Integer.valueOf(config.getInitParameter("cachePort"));
		int cacheTimeout = Integer.valueOf(config.getInitParameter("cacheTimeout"));
		jedis = new Jedis(cacheHost, cachePort, cacheTimeout);
		try {
			jedis.connect();
		} catch (UnknownHostException e) {
			throw new ServletException(e);
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
						if (tryTime == 5) {
							log.error("init redis fail, try times:" + tryTime);
							break;
						}
						jedis.psubscribe(new JedisPubSub() {

							@Override
							public void onMessage(String channel, String message) {

							}

							@Override
							public void onPMessage(String pattern, String channel, String message) {
								try {
									String[] items = StringUtils.split(message, ":", 2);
									pushMsg(items[0], items[1]);
								} catch (Exception e) {
									log.error("message invalid:" + message + " channel:" + channel, e);
								}
							}

							@Override
							public void onSubscribe(String channel, int subscribedChannels) {

							}

							@Override
							public void onUnsubscribe(String channel, int subscribedChannels) {

							}

							@Override
							public void onPUnsubscribe(String pattern, int subscribedChannels) {
								log.error("onPUnsubscribe:" + pattern);
							}

							@Override
							public void onPSubscribe(String pattern, int subscribedChannels) {

							}
						}, "msg-*");
					} catch (Exception e) {
						log.error("", e);
					}
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						log.error("", e);
					}
				}
			}
		}).start();
	}

	private ConcurrentHashMap<String, List<PushExecutor>> executorMap = new ConcurrentHashMap<String, List<PushExecutor>>();

	@Override
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
		doPost(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String[] peopleIds = StringUtils.split(req.getParameter("peopleIds"), ',');
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
		AsyncContext ctx = req.startAsync(req, resp);
		ctx.setTimeout(aliveTime);
		ctx.addListener(new AsyncListener() {

			@Override
			public void onComplete(AsyncEvent event) throws IOException {
				log.debug("onComplete");
			}

			@Override
			public void onTimeout(AsyncEvent event) throws IOException {
				log.debug("onTimeout");
			}

			@Override
			public void onError(AsyncEvent event) throws IOException {
				log.debug("onError");
			}

			@Override
			public void onStartAsync(AsyncEvent event) throws IOException {
				log.debug("onStartAsync");
			}
		});
		PushExecutor exe = new PushExecutor(ctx);
		log.debug("new executor");
		addExecutor(peopleIds, exe);
		new Thread(exe).start();
	}

	private void pushMsg(String peopleId, String msg) {
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
						log.debug("remove exe, peopleId:" + peopleId);
					}
					continue;
				}
				try {
					exe.putMsg(msg);
				} catch (InterruptedException e) {
					log.error("", e);
				}
			}
		}
	}

	/**
	 * @param peopleIds
	 * @param exe
	 */
	private void addExecutor(String[] peopleIds, PushExecutor exe) {
		for (String peopleId : peopleIds) {
			List<PushExecutor> list = executorMap.get(peopleId);
			if (list == null) {
				list = new ArrayList<PushExecutor>();
				executorMap.put(peopleId, list);
			}
			synchronized (list) {
				list.add(exe);
			}
		}
	}
}
