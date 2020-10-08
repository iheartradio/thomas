package com.iheart.thomas.admin

import java.time.Instant

case class AuthRecord(
    id: String,
    jwtEncoded: String,
    identity: String,
    expiry: Instant,
    lastTouched: Option[Instant])

trait AuthRecordDAO[F[_]] {
  def insert(record: AuthRecord): F[AuthRecord]

  def update(record: AuthRecord): F[AuthRecord]

  def remove(id: String): F[Unit]

  def find(id: String): F[Option[AuthRecord]]
}
