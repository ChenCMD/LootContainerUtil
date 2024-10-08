package com.github.chencmd.lootcontainermanager.feature.asset

import org.bukkit.inventory.ItemStack

type ItemIdentifier = String

trait ItemConversionInstr[F[_], G[_]] {
  def toItemIdentifier(item: ItemStack): F[ItemIdentifier]
  def toItemStack(itemIdentifier: ItemIdentifier): G[ItemStack]
}
