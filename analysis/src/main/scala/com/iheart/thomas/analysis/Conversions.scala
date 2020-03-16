package com.iheart.thomas
package analysis

case class Conversions(
    converted: Long,
    total: Long) {
  assert(
    converted <= total,
    s"Cannot crate a conversions whose converted count $converted is more than total count $total"
  )
  def rate = converted.toDouble / total.toDouble

  override def toString: String =
    s"Conversions(converted: $converted, total: $total, rate: ${(rate * 100).formatted("%.2f")}%)"
}
