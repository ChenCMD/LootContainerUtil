package com.github.chencmd.lootcontainerutil.generic.extensions

import cats.effect.IO
import cats.effect.unsafe.IORuntime

object IOExt {
  extension [A](io: IO[A]) {
    def unsafeRunHereSync()(using runtime: IORuntime): A = {
      io.syncStep(1).unsafeRunSync() match {
        case Left(v)  => v.unsafeRunSync()
        case Right(v) => v
      }
    }
  }
}
