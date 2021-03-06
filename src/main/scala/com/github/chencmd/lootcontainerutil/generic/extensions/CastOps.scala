package com.github.chencmd.lootcontainerutil.generic.extensions

import scala.reflect.TypeTest

object CastOps {
  extension [A](value: A) {
    def downcastOrNone[B](using tt: TypeTest[A, B]): Option[A & B] =
      value match {
        case tt(b) => Some(b)
        case _     => None
      }
  }
}
