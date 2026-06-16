package com.alas.dashboard.android.core.model

private val ResourceDisplayNames = mapOf(
    "Oil" to "石油",
    "Coin" to "物资",
    "Gem" to "钻石",
    "Cube" to "魔方",
    "Pt" to "活动PT",
    "YellowCoin" to "大世界黄币",
    "PurpleCoin" to "大世界紫币",
    "ActionPoint" to "行动力",
    "Merit" to "功勋",
    "Medal" to "勋章",
    "Core" to "核心数据",
    "GuildCoin" to "舰队币",
)

private val ResourceOrder = listOf(
    "Oil",
    "Coin",
    "Gem",
    "Pt",
    "Cube",
    "ActionPoint",
    "YellowCoin",
    "PurpleCoin",
    "Core",
    "Medal",
    "Merit",
    "GuildCoin",
).withIndex().associate { it.value to it.index }

fun String.displayResourceName(): String = ResourceDisplayNames[this] ?: this

fun resourceDisplayOrder(resourceName: String): Int = ResourceOrder[resourceName] ?: Int.MAX_VALUE

fun Iterable<String>.sortedResourceNames(): List<String> =
    toList().sortedBy(::resourceDisplayOrder)

fun Iterable<ResourceSnapshot>.sortedByResourceDisplayOrder(): List<ResourceSnapshot> =
    toList().sortedBy { resourceDisplayOrder(it.resourceName) }
