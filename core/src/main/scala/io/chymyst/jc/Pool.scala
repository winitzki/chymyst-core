package io.chymyst.jc


import java.util.concurrent._

class FixedPool(threads: Int) extends PoolExecutor(threads, { t =>
  val queue = new LinkedBlockingQueue[Runnable]
  (new ThreadPoolExecutor(t, t, 0L, TimeUnit.SECONDS, queue, new ThreadFactoryWithInfo), queue)
})

/** A pool of execution threads, or another way of running tasks (could use actors or whatever else).
  * Tasks submitted for execution can have an optional name (useful for debugging).
  * The pool can be shut down, in which case all further tasks will be refused.
  */
trait Pool {
  def shutdownNow(): Unit

  def runClosure(closure: => Unit, info: ChymystThreadInfo): Unit

  def runRunnable(runnable: Runnable): Unit

  def isInactive: Boolean
}

/** Basic implementation of a thread pool.
  *
  * @param threads     Initial number of threads.
  * @param execFactory Dependency injection closure.
  */
private[jc] class PoolExecutor(threads: Int = 8, execFactory: Int => (ExecutorService, BlockingQueue[Runnable])) extends Pool {
  protected val (execService: ExecutorService, queue: BlockingQueue[Runnable]) = execFactory(threads)

  val sleepTime = 200L

  def shutdownNow(): Unit = new Thread {
    try {
      queue.clear()
      execService.shutdown()
      execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
    } finally {
      execService.shutdownNow()
      execService.awaitTermination(sleepTime, TimeUnit.MILLISECONDS)
      execService.shutdownNow()
      ()
    }
  }.start()

  def runClosure(closure: => Unit, info: ChymystThreadInfo): Unit =
    execService.execute(new RunnableWithInfo(closure, info))

  override def isInactive: Boolean = execService.isShutdown || execService.isTerminated

  override def runRunnable(runnable: Runnable): Unit = execService.execute(runnable)
}

/** Create a pool from a Handler interface. The pool will submit tasks using a Handler.post() method.
  *
  * This is useful for Android and JavaFX environments. Not yet tested. Behavior with static molecules will be probably wrong.
  *
  * Note: the structural type for `handler` should not be used. Here it is used only as an illustration.
  */
/*
class HandlerPool(handler: { def post(r: Runnable): Unit }) extends Pool {
  override def shutdownNow(): Unit = ()

  override def runClosure(closure: => Unit, info: ReactionInfo): Unit =
    handler.post(new RunnableWithInfo(closure, info))

  override def isInactive: Boolean = false
}
*/
