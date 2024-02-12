package com.github.chencmd.lootcontainerutil

import com.github.chencmd.lootcontainerutil.feature.containerprotection.ProtectActionListener
import com.github.chencmd.lootcontainerutil.minecraft.OnMinecraftThread
import com.github.chencmd.lootcontainerutil.generic.EitherTIOExtra.*
import com.github.chencmd.lootcontainerutil.utils.CommonErrorHandler.given

import cats.data.EitherT
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import cats.implicits.*

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import cats.data.NonEmptyChain

class LootContainerUtil extends JavaPlugin {
  type F = EitherT[IO, String, _]

  val cmdExecutor: Ref[F, Option[CommandExecutor[F]]] = Ref.unsafe(None)

  override def onEnable() = {
    given OnMinecraftThread[F] = new OnMinecraftThread[F](this)

    val program = EitherT[IO, NonEmptyChain[String], Config](Config.tryRead(this)).flatMap { cfg =>
      val program = for {
        _ <- Async[F].delay(Bukkit.getPluginManager.registerEvents(new ProtectActionListener, this))
        given Config = cfg
        _ <- cmdExecutor.set(Some(new CommandExecutor))
        _ <- Async[F].delay(Bukkit.getConsoleSender.sendMessage("LootContainerUtil enabled."))
      } yield ()
      program.leftMap(NonEmptyChain.one)
    }
    program.catchError.unsafeRunSync()
  }

  override def onCommand(
    sender: CommandSender,
    command: Command,
    label: String,
    args: Array[String]
  ): Boolean = {
    if (command.getName == "lcu") {
      cmdExecutor.get.flatMap(_.traverse_(_.run(sender, args.toList))).catchError.unsafeRunAndForget()
      true
    } else {
      false
    }
  }
}
