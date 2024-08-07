package com.github.chencmd.lootcontainerutil.minecraft.bukkit

import com.github.chencmd.lootcontainerutil.exceptions.SystemException

import cats.effect.kernel.Sync
import cats.implicits.*

import scala.jdk.CollectionConverters.*

import org.bukkit.Bukkit
import org.bukkit.Location as BukkitLocation
import org.bukkit.World

final case class BlockLocation(w: String, x: Int, y: Int, z: Int) {
  infix def +(other: Position): Position = Position(w, x + other.x, y + other.y, z + other.z)
  infix def +(vec: Vector): Position     = Position(w, x + vec.x, y + vec.y, z + vec.z)

  infix def -(other: Position): Position = Position(w, x - other.x, y - other.y, z - other.z)
  infix def -(vec: Vector): Position     = Position(w, x - vec.x, y - vec.y, z - vec.z)

  infix def *(other: Position): Position = Position(w, x * other.x, y * other.y, z * other.z)
  infix def *(vec: Vector): Position     = Position(w, x * vec.x, y * vec.y, z * vec.z)

  infix def /(other: Position): Position = Position(w, x / other.x, y / other.y, z / other.z)
  infix def /(vec: Vector): Position     = Position(w, x / vec.x, y / vec.y, z / vec.z)

  infix def *(n: Double): Position = Position(w, x * n, y * n, z * n)

  infix def /(n: Double): Position = Position(w, x / n, y / n, z / n)

  def toChunkLocation: BlockLocation = BlockLocation(w, x >> 4, y >> 4, z >> 4)

  def toBukkit(world: World): BukkitLocation  = new BukkitLocation(world, x, y, z)
  def toBukkit[F[_]: Sync]: F[BukkitLocation] = for {
    worldOpt <- Sync[F].delay(Bukkit.getWorlds().asScala.toList.find(_.getKey.toString == w))
    world    <- worldOpt.fold(SystemException.raise(s"Missing world: $w"))(_.pure[F])
  } yield new BukkitLocation(world, x, y, z)

  def toPosition: Position = Position(w, x.toDouble, y.toDouble, z.toDouble)
  def toVector: Vector     = Vector(x.toDouble, y.toDouble, z.toDouble)

  def toXYZString: String = s"$x, $y, $z"
}

object BlockLocation {
  def of(l: BukkitLocation): BlockLocation =
    new BlockLocation(l.getWorld.getKey.toString, l.getBlockX, l.getBlockY, l.getBlockZ)
}
