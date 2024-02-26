package com.github.chencmd.lootcontainerutil.feature.genasset

import com.github.chencmd.lootcontainerutil.generic.extensions.CastOps.*
import com.github.chencmd.lootcontainerutil.minecraft.OnMinecraftThread
import com.github.chencmd.lootcontainerutil.minecraft.Location
import com.github.chencmd.lootcontainerutil.minecraft.Vector
import com.github.chencmd.lootcontainerutil.feature.genasset.persistence.LootAssetPersistenceInstr

import cats.effect.SyncIO
import cats.effect.kernel.Async
import cats.implicits.*
import cats.mtl.Raise

import org.bukkit.block.BlockFace
import org.bukkit.block.data.`type`.Chest
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.Container
import org.bukkit.entity.Player
import com.github.chencmd.lootcontainerutil.feature.genasset.persistence.LootAsset
import com.github.chencmd.lootcontainerutil.feature.genasset.persistence.LootAssetItem

object GenLootAsset {
  val acc = 5
  def generateLootAsset[F[_]: Async](
    p: Player
  )(using
    R: Raise[F, String],
    mcThread: OnMinecraftThread[F],
    Converter: ItemConversionInstr[F],
    LAP: LootAssetPersistenceInstr[F]
  ): F[Unit] = {
    val action = for {
      blockDataOpt                                               <- mcThread.run(SyncIO {
        val loc           = p.getEyeLocation
        val vec           = Vector.of(loc.getDirection).normalize * (1d / acc)
        val w             = p.getWorld
        val targetedBlock = (0 until (5 * acc)).view
          .map(i => w.getBlockAt((Location.of(loc) + vec * i).toBukkit))
          .flatMap(_.getState.downcastOrNone[Container])
          .headOption

        targetedBlock.map { block =>
          val data = block.getBlockData()
          (
            Location.ofInt(block.getLocation()),
            block.getType().getKey().toString(),
            data.downcastOrNone[Directional].map(_.getFacing),
            data.downcastOrNone[Waterlogged].map(_.isWaterlogged),
            data.downcastOrNone[Chest].map(_.getType),
            block.getInventory().getContents().toList
          )
        }
      })
      (location, blockId, facing, waterlogged, chestType, items) <-
        blockDataOpt.fold(R.raise("No container was found."))(_.pure[F])

      /*
       * "tag.TSB.ID"           -> ["artifact:%%tag.TSB.ID%%",                       "1",         ""       ]
       * "tag.TSB{Currency:1b}" -> ["preset:currency/",                              "%%Count%%", ""       ]
       * "tag.TSB{Currency:2b}" -> ["preset:currency/high",                          "%%Count%%", ""       ]
       * "tag.TSB.ShardRarity"  -> ["preset:artifact_shard/%%tag.TSB.ShardRarity%%", "%%Count%%", ""       ]
       * "{}"                   -> ["%%id%%",                                        "%%Count%%", "%%tag%%"]
       */

      asset <- items
        .traverseWithIndexM { (item, slot) =>
          Converter.toItemIdentifier(item).map(LootAssetItem(slot, _, item.getAmount()))
        }
        .map { stringifiedItems =>
          LootAsset(
            location,
            blockId,
            facing.map(_.toString().toLowerCase()),
            waterlogged,
            chestType.map(_.toString().toLowerCase()),
            stringifiedItems
          )
        }

      _ <- LAP.storeLootAsset(asset)
    } yield ()

    action
  }
}
