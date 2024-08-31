package com.github.chencmd.lootcontainermanager.feature.asset

import com.github.chencmd.lootcontainermanager.Prefix
import com.github.chencmd.lootcontainermanager.exceptions.UserException
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAsset
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAssetContainer
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAssetItem
import com.github.chencmd.lootcontainermanager.feature.asset.persistence.LootAssetPersistenceCacheInstr
import com.github.chencmd.lootcontainermanager.generic.extensions.CastOps.*
import com.github.chencmd.lootcontainermanager.minecraft.OnMinecraftThread
import com.github.chencmd.lootcontainermanager.minecraft.bukkit.BlockLocation
import com.github.chencmd.lootcontainermanager.minecraft.bukkit.Vector

import cats.data.OptionT
import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.effect.std.UUIDGen
import cats.implicits.*

import scala.jdk.CollectionConverters.*

import org.bukkit.World
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.`type`.Chest as ChestData
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

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
  }

  def generateLootAsset[F[_]: Async, G[_]: Sync](p: Player)(using
    mcThread: OnMinecraftThread[F, G],
    Converter: ItemConversionInstr[F, G],
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
    (data, connected)   <- containerDataOrNone.fold(UserException.raise[F]("No container was found."))(_.pure[F])

    // アセットが既に存在しているか確認する
    existsAsset <- asyncLootAssetCache.askIfLootAssetPresentAt(data.location)
    _           <- Async[F].whenA(existsAsset) {
      UserException.raise(s"${Prefix.ERROR}既にアセットとして登録されているコンテナです。")
    }

    // コンテナを見ているプレイヤーが居たら閉じる
    _           <- closeContainer(data.inventory)

    // コンテナの情報を取得する
    items <- convertToLootAssetItem[F, G](data.inventory)
    asset <- convertToLootAsset(List(data) ++ connected.toList, items)

    // LootAsset を保存する
    _ <- asyncLootAssetCache.updateLootAsset(asset)

    // プレイヤーにメッセージを送信する
    _ <- Async[F].delay {
      p.sendMessage(s"${Prefix.SUCCESS}アセットを生成しました。")
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
      "inventory"   -> container.getInventory()
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

  def convertToLootAsset[F[_]: Async](data: List[ContainerData], items: List[LootAssetItem]): F[LootAsset] = {
    val containers = data.map(_(unselect.name.inventory).to[LootAssetContainer])
    val uuid       = UUIDGen.randomUUID[F]
    uuid.map(LootAsset(None, _, data.head.name, containers, items))
  }
}
