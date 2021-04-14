package lihua
package mongo

import cats.data.NonEmptyList

sealed trait DBError
    extends RuntimeException
    with Product
    with Serializable

object DBError {
  case object NotFound extends DBError

  case class DBException(
      throwable: Throwable,
      collection: String)
      extends DBError {
    override def getCause: Throwable = throwable

    override def getMessage: String =
      s"Error occurred (collection: $collection): ${throwable.getMessage} "
  }
  case class DBLastError(override val getMessage: String) extends DBError

  case class WriteError(details: NonEmptyList[WriteErrorDetail])
      extends DBError {
    override def getMessage: String = details.toString()
  }

  sealed trait WriteErrorDetail extends Product with Serializable {
    def code: Int
    def msg: String
    override def toString: String = s"code: $code, message: $msg"
  }

  case class ItemWriteErrorDetail(
      code: Int,
      msg: String)
      extends WriteErrorDetail
  case class WriteConcernErrorDetail(
      code: Int,
      msg: String)
      extends WriteErrorDetail

  case class UpdatedCountErrorDetail(
      expectedCount: Int,
      actual: Int)
      extends DBError {
    override def getMessage =
      s"updated count is $actual, expected $expectedCount"
  }
}
