package com.github.chencmd.lootcontainermanager.feature.asset

import com.github.chencmd.lootcontainermanager.Prefix
import com.github.chencmd.lootcontainermanager.exceptions.UserException
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAsset
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAssetPersistenceCacheInstr
import com.github.chencmd.lootcontainermanager.generic.extensions.CastExt.*
import com.github.chencmd.lootcontainermanager.generic.extensions.OptionExt.*
import com.github.chencmd.lootcontainermanager.minecraft.OnMinecraftThread
import com.github.chencmd.lootcontainermanager.minecraft.bukkit.BlockLocation
import com.github.chencmd.lootcontainermanager.terms.InventoriesStore
import com.github.chencmd.lootcontainermanager.terms.InventoriesStore.*

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.implicits.*
import org.typelevel.log4cats.Logger

import scala.jdk.CollectionConverters.*
import scala.util.chaining.*

import org.bukkit.block.Container
import org.bukkit.entity.Player

object DelLootAsset {
  def deleteLootAsset[F[_]: Async, G[_]: Sync](p: Player, openedInventories: InventoriesStore[F], debug: Boolean)(using
    logger: Logger[F],
    mcThread: OnMinecraftThread[F, G],
    itemConverter: ItemConversionInstr[F, G],
    asyncLootAssetCache: LootAssetPersistenceCacheInstr[F]
  ): F[Unit] = for {
    dataOrNone                <- mcThread.run(for {
      blockOrNone     <- Sync[G].delay(Option(p.getTargetBlockExact(5)))
      containerOrNone <- Sync[G].delay(blockOrNone.flatMap(_.getState.downcastOrNone[Container]))
      inventoryOrNone <- Sync[G].delay(containerOrNone.map(_.getInventory()))
    } yield for {
      container <- containerOrNone
      inventory <- inventoryOrNone
    } yield container -> inventory)
    (container, containerInv) <- dataOrNone.orRaiseF[F](UserException("No container was found"))

    assetLocation = BlockLocation.of(container.getLocation())

    asset <- asyncLootAssetCache.askLootAssetLocationAt(assetLocation)
    asset <- asset.orRaiseF[F](UserException("Asset not found"))

    _ <- asset match {
      case asset: LootAsset.Fixed => for {
          (_, assetSession) <- openedInventories.getOrCreateInventory[G](assetLocation, asset, debug)
          assetInv = assetSession.getInventory()

          _ <- Async[F].delay { assetInv.getViewers().asScala.foreach(_.closeInventory()) }
          _ <- Async[F].delay {
            containerInv.clear()
            containerInv.setContents(assetInv.getContents())
          }
        } yield ()
      case _: LootAsset.Random    => Async[F].unit
    }

    _ <- openedInventories.withLockAtKey(assetLocation)(_ => (None, ()).pure[F])
    _ <- asyncLootAssetCache.deleteLootAssetLocationAt(assetLocation)

    _ <- Async[F].delay { p.sendMessage(s"${Prefix.SUCCESS}アセットを削除しました。") }
  } yield ()
}
