package lihua

import reactivemongo.api.bson.BSONObjectID

package object mongo {
  def generateId: EntityId = EntityId(BSONObjectID.generate.stringify)
}
