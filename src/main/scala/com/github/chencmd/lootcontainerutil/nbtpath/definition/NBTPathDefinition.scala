package com.github.chencmd.lootcontainerutil.nbtpath.definition

case class NBTPath(root: NBTPathRootNode, nodes: List[NBTPathNode])

enum NBTPathRootNode {
  case MatchRootObject(pattern: CompoundTag)
  case MatchObject(name: String, pattern: CompoundTag)
  case CompoundChild(name: String)
}

enum NBTPathNode {
  case MatchObject(name: String, pattern: CompoundTag)
  case AllElements()
  case MatchElement(pattern: CompoundTag)
  case IndexedElement(index: Int)
  case CompoundChild(name: String)
}

case class CompoundTag(value: Map[String, CompoundValue])

type CompoundPair = (String, CompoundValue)

enum CompoundValue {
  case VCompound(value: CompoundTag)
  case VString(value: String)
  case VByte(value: Byte)
  case VShort(value: Short)
  case VInt(value: Int)
  case VLong(value: Long)
  case VFloat(value: Float)
  case VDouble(value: Double)
  case VCompoundList(value: List[CompoundTag])
  case VStringList(value: List[String])
  case VByteList(value: List[Byte])
  case VShortList(value: List[Short])
  case VIntList(value: List[Int])
  case VLongList(value: List[Long])
  case VFloatList(value: List[Float])
  case VDoubleList(value: List[Double])
}
