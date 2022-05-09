package com.github.chencmd.lootcontainerutil

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.chencmd.lootcontainerutil.generic.extensions.CastOps.downcastOrNone
import org.bukkit.{Bukkit, ChatColor}
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.world.LootGenerateEvent
import org.bukkit.event.{EventHandler, Listener}
import org.bukkit.loot.{LootTable, Lootable}
import org.bukkit.metadata.{FixedMetadataValue, MetadataValue}
import org.bukkit.plugin.java.JavaPlugin

import scala.util.chaining.*
import scala.jdk.CollectionConverters.*

class ProtectActionListener(plugin: JavaPlugin, ignorePlayerSet: IgnorePlayerSet) extends Listener {
  Bukkit.getPluginManager.registerEvents(this, plugin)

  @EventHandler def onLootGenerate(e: LootGenerateEvent): Unit = {
    val pOpt = e.getEntity.downcastOrNone[Player]

    if (pOpt.isEmpty) {
      return
    }

    val p: Player = pOpt.get

    val action = for {
      isIgnoreProtect <- ignorePlayerSet.isIgnorePlayer(p)

      _ <- IO.unlessA(isIgnoreProtect) {
        val lootTableKey = e.getLootTable.getKey
        val cmd = e.getLootContext
          .getLocation
          .getBlock
          .pipe(l => s"data modify block ${l.getX} ${l.getY} ${l.getZ} LootTable set value \"$lootTableKey\"")

        IO {
          Bukkit.dispatchCommand(p, cmd)

          p.sendMessage(s"${Prefix.INFO}ルートテーブルが設定されているため開くことができませんでした。")
          p.sendMessage(s"${Prefix.INFO}意図して開く場合は、${ChatColor.GOLD}/lcu ignore${ChatColor.WHITE}を実行してください。")
          p.sendMessage(s"${Prefix.INFO}設定されているルートテーブル: $lootTableKey")

          p.setMetadata("generateCancelled", FixedMetadataValue(plugin, Bukkit.getWorlds.asScala.head.getGameTime))
        }
      }
    } yield ()

    action.unsafeRunSync()
  }

  @EventHandler def onContainerOpen(e: InventoryOpenEvent): Unit = {

    val p = e.getPlayer.asInstanceOf[Player]

    val action = IO {
      val res = p.getMetadata("generateCancelled")
        .asScala
        .headOption
        .flatMap(_.value.downcastOrNone[Long])
        .exists(Bukkit.getWorlds.asScala.head.getGameTime - _ <= 10)
      p.removeMetadata("generateCancelled", plugin)
      res
    }

    val isCancelled = action.unsafeRunSync()
    if (isCancelled) {
      e.setCancelled(true)
    }
  }
}
