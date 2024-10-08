package com.github.chencmd.lootcontainermanager.feature.asset

import com.github.chencmd.lootcontainermanager.Prefix
import com.github.chencmd.lootcontainermanager.exceptions.UserException
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAsset
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAssetContainer
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAssetItem
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAssetPersistenceCacheInstr
import com.github.chencmd.lootcontainermanager.generic.extensions.CastExt.*
import com.github.chencmd.lootcontainermanager.generic.extensions.OptionExt.*
import com.github.chencmd.lootcontainermanager.minecraft.OnMinecraftThread
import com.github.chencmd.lootcontainermanager.minecraft.bukkit.BlockLocation
import com.github.chencmd.lootcontainermanager.minecraft.bukkit.Vector

import cats.data.OptionT
import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.effect.std.UUIDGen
import cats.implicits.*

import scala.jdk.CollectionConverters.*

import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.`type`.Chest as ChestData
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.loot.Lootable

import com.github.tarao.record4s.%
import com.github.tarao.record4s.unselect

object GenLootAsset {
  type ContainerData = % {
    val location: BlockLocation
    val blockId: String
    val name: Option[String]
    val facing: Option[org.bukkit.block.BlockFace]
    val waterlogged: Option[Boolean]
    val chestType: Option[ChestData.Type]
    val inventory: Inventory
    val lootTable: Option[NamespacedKey]
  }

  def generateLootAsset[F[_]: Async, G[_]: Sync](p: Player)(using
    mcThread: OnMinecraftThread[F, G],
    itemConverter: ItemConversionInstr[F, G],
    asyncLootAssetCache: LootAssetPersistenceCacheInstr[F]
  ): F[Unit] = for {
    // プレイヤーが見ているコンテナの情報を取得する
    containerDataOrNone <- mcThread.run {
      val program = for {
        container     <- OptionT(findContainer[G](p))
        containerData <- OptionT.liftF(getContainerData[G](container))

        connectedContainer     <- OptionT.liftF(getConnectedContainer[G](p.getWorld(), containerData))
        connectedContainerData <- OptionT.liftF(connectedContainer.traverse(getContainerData[G]))
      } yield (containerData, connectedContainerData)
      program.value
    }
    (data, connected)   <- containerDataOrNone.orRaiseF[F](UserException("No container was found."))

    // アセットが既に存在しているか確認する
    existsAsset <- asyncLootAssetCache.askIfLootAssetPresentAt(data.location)
    _           <- Async[F].whenA(existsAsset) {
      UserException.raise(s"${Prefix.ERROR}既にアセットとして登録されているコンテナです。")
    }

    // コンテナを見ているプレイヤーが居たら閉じる
    _           <- closeContainer(data.inventory)

    // アセットを生成する
    containers = convertToLootAssetContainers(data, connected)
    assetUuid <- UUIDGen.randomUUID[F]
    asset     <- data.lootTable match {
      case Some(lt) => LootAsset.Random(None, assetUuid, data.name, containers, lt).pure[F]
      case None     => convertToLootAssetItem[F, G](data.inventory).map { items =>
          LootAsset.Fixed(None, assetUuid, data.name, containers, items)
        }
    }

    // LootAsset を保存する
    _         <- asyncLootAssetCache.updateLootAsset(asset)

    // プレイヤーにメッセージを送信する
    _ <- Async[F].delay {
      asset match {
        case _: LootAsset.Fixed  => p.sendMessage(s"${Prefix.SUCCESS}アセット (固定) を生成しました。")
        case _: LootAsset.Random => p.sendMessage(s"${Prefix.SUCCESS}アセット (ランダム) を生成しました。")
      }
    }
  } yield ()

  def findContainer[G[_]: Sync](p: Player): G[Option[Container]] = Sync[G].delay {
    Option(p.getTargetBlockExact(5))
      .flatMap(_.getState.downcastOrNone[Container])
      .headOption
  }

  def getConnectedContainer[G[_]: Sync](world: World, data: ContainerData): G[Option[Container]] = {
    val program = for {
      facing    <- OptionT.fromOption[G](data.facing)
      chestType <- OptionT.fromOption[G](data.chestType)
      rotation  <- OptionT.fromOption[G](chestType match {
        case ChestData.Type.SINGLE => None
        case ChestData.Type.LEFT   => Some(+90)
        case ChestData.Type.RIGHT  => Some(-90)
      })

      location                   = data.location
      vector                     = Vector.of(facing.getDirection).rotate(rotation)
      connectedContainerLocation = (location + vector).toBukkit(world)

      container <- OptionT(Sync[G].delay {
        world.getBlockAt(connectedContainerLocation).getState.downcastOrNone[Container & Chest]
      })

      ccFacing <- OptionT(Sync[G].delay {
        val data = container.getBlockData()
        data.downcastOrNone[Directional].map(_.getFacing)
      })
      if ccFacing == facing

      ccChestType <- OptionT(Sync[G].delay {
        val data = container.getBlockData()
        data.downcastOrNone[ChestData].map(_.getType)
      })
      if chestType match {
        case ChestData.Type.SINGLE => true
        case ChestData.Type.LEFT   => ccChestType == ChestData.Type.RIGHT
        case ChestData.Type.RIGHT  => ccChestType == ChestData.Type.LEFT
      }
    } yield container
    program.value.widen
  }

  def getContainerData[G[_]: Sync](container: Container): G[ContainerData] = Sync[G].delay {
    val data = container.getBlockData()
    %(
      "location"    -> BlockLocation.of(container.getLocation()),
      "blockId"     -> container.getType().getKey().toString(),
      "name"        -> Option(container.getCustomName()),
      "facing"      -> data.downcastOrNone[Directional].map(_.getFacing),
      "waterlogged" -> data.downcastOrNone[Waterlogged].map(_.isWaterlogged),
      "chestType"   -> data.downcastOrNone[ChestData].map(_.getType),
      "inventory"   -> container.getInventory(),
      "lootTable"   -> container.downcastOrNone[Lootable].flatMap(l => Option(l.getLootTable)).map(_.getKey())
    )
  }

  def closeContainer[F[_]: Async](inv: Inventory): F[Unit] = {
    inv.getViewers().asScala.toList.traverse_(p => Async[F].delay(p.closeInventory()))
  }

  def convertToLootAssetItem[F[_]: Async, G[_]: Sync](
    inv: Inventory
  )(using Converter: ItemConversionInstr[F, G]): F[List[LootAssetItem]] = {
    inv
      .getContents()
      .toList
      .map(Option.apply)
      .zipWithIndex
      .traverseCollect {
        case (Some(item), slot) => Converter.toItemIdentifier(item).map(LootAssetItem(slot, _, item.getAmount()))
      }
  }

  def convertToLootAssetContainers(
    data: ContainerData,
    connectedContainerData: Option[ContainerData]
  ): List[LootAssetContainer] = {
    val containers = List(data) ++ connectedContainerData.toList
    containers.map(_(unselect.name.inventory).to[LootAssetContainer])
  }
}
