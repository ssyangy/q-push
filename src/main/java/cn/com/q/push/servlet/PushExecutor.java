/**
 * 
 */
package cn.com.q.push.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;

import org.apache.log4j.Logger;

/**
 * @author seanlinwang at gmail dot com
 * @date May 4, 2011
 * 
 */
public class PushExecutor implements Runnable {
	private static final Logger log = Logger.getLogger(PushServlet.class);

	private static final int MAX_LINE_POOL_SIZE = 1024 * 10;

	private static final int POLL_TIMEOUT = 1000;

	private long aliveTime = -1;

	private boolean closed = false;

	private AsyncContext ctx;

	private BlockingQueue<Object> msgPool = new ArrayBlockingQueue<Object>(MAX_LINE_POOL_SIZE);

	private final Object eof = new Object();

	private String cmd;

	public boolean isEnded() {
		return this.closed == true;
	}

	public void setAliveTime(long aliveTime) {
		this.aliveTime = aliveTime;
	}

	public String getCmd() {
		return this.cmd;
	}

	public PushExecutor(AsyncContext ctx, String cmd) {
		this.cmd = cmd;
		this.ctx = ctx;
		this.ctx.addListener(new AsyncListener() {

			@Override
			public void onComplete(AsyncEvent event) throws IOException {
				log.debug("onComplete");
			}

			@Override
			public void onTimeout(AsyncEvent event) throws IOException {
				log.debug("onTimeout");
				end();
			}

			@Override
			public void onError(AsyncEvent event) throws IOException {
				log.debug("onError");
				end();
			}

			@Override
			public void onStartAsync(AsyncEvent event) throws IOException {
				log.debug("onStartAsync");
			}
		});
	}

	public void end() {
		putMsg(eof);
	}

	public void putMsg(Object msg) {
		try {
			msgPool.put(msg);
		} catch (Exception e) {
			log.error("", e);
		}
	}

	public void run() {
		long startTime = System.currentTimeMillis();
		PrintWriter out = null;
		try {
			out = ctx.getResponse().getWriter();
			Object msg = null;
			while (true) {
				if (aliveTime > 0 && System.currentTimeMillis() - startTime > aliveTime) {
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
					if (log.isDebugEnabled()) {
						log.debug("write msg:" + msg);
					}
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
		if (log.isDebugEnabled()) {
			log.debug("executor closed:" + this);
		}
	}
}
