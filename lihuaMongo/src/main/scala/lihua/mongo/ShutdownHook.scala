/*
* Copyright [2017] [iHeartMedia Inc]
* All rights reserved
*/
package lihua
package mongo

trait ShutdownHook {
  def onShutdown[T](code: ⇒ T): Unit
}


object ShutdownHook {

  /**
    * a shutdownhook
    */
  object ignore extends ShutdownHook {
    override def onShutdown[T](code: => T): Unit = ()
  }



  class Manual extends ShutdownHook {
    @volatile
    private[mongo] var callbacks: List[() => _] = Nil
    override def onShutdown[T](code: => T): Unit = {
      callbacks = (() => code) :: callbacks
    }
    def shutdown(): Unit = callbacks.foreach(_())
  }

  def manual = new Manual
}
