package com.bijnor.game

/** A place name of the Bijnor map file (English + Hindi) at world x / z (metres, north = -z). */
class MapLabel(val en: String, val hi: String, val x: Float, val z: Float)

/** The 32 place names that the Bijnor map file carries (city / town labels), listed from the biggest towns down. */
object BijnorLabels {
    val ALL: List<MapLabel> = listOf(
        MapLabel("Bijnor", "बिजनौर", -1522f, 78f),
        MapLabel("Nagina", "नगीना", -214f, -351f),
        MapLabel("Dhampur", "धामपुर", 146f, 375f),
        MapLabel("Sherkot", "शेरकोट", 357f, 225f),
        MapLabel("Seohara", "स्योहारा", 466f, 884f),
        MapLabel("Chandpur", "चांदपुर", -868f, 1345f),
        MapLabel("Nehtaur", "नहटौर", -388f, 329f),
        MapLabel("Haldaur Rural", "हल्दौर रूरल", -891f, 556f),
        MapLabel("Kotawali", "कोतवाली", -574f, -273f),
        MapLabel("Jhalu", "झालू", -1176f, 269f),
        MapLabel("Kiratpur", "किरतपुर", -1299f, -626f),
        MapLabel("Mandawar", "मंडावर", -1697f, -527f),
        MapLabel("Noorpur", "नूरपुर", -259f, 1251f),
        MapLabel("Raja Ka Tajpur", "राजा का ताजपुर", 72f, 1166f),
        MapLabel("Najibabad", "नजीबाबाद", -717f, -1227f),
        MapLabel("Afzalgarh", "अफ़ज़लगढ़", 881f, -116f),
        MapLabel("Sahanpur", "सहनपुर", -819f, -1342f),
        MapLabel("Jalalabad", "जलालाबाद", -847f, -1152f),
        MapLabel("Dara Nagar Ganj", "दारानगर गंज", -1520f, 659f),
        MapLabel("Kalagarh", "कालागढ़", 1120f, -450f),
        MapLabel("Pheona", "फेओना", -523f, 1667f),
        MapLabel("Amhera", "अमहेड़ा", -855f, 727f),
        MapLabel("Paijaniya", "पैजनिया", -601f, 892f),
        MapLabel("Akbarabad", "अकबराबाद", -619f, -496f),
        MapLabel("Baruki", "बरूकी", -1085f, -74f),
        MapLabel("Nangal Jat", "नंगल जट", -945f, 297f),
        MapLabel("Phatehpur", "फ़तेहपुर", -1290f, 586f),
        MapLabel("Uledha", "उलेधा", -1205f, 772f),
        MapLabel("Surjan Nagar", "सुरजन नगर", 977f, 762f),
        MapLabel("Suawala", "सुअवाला", 762f, 367f),
        MapLabel("Harewali", "हरेवाली", 490f, -138f),
        MapLabel("Suvaheri", "सुवाहेरी", -1418f, -393f)
    )
}
