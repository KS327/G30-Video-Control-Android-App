package sg.hexcel.tankeramr.control

enum class TurnButton(val wireName: String, val shortName: String) {
    L1_CENTER_ACW("L1_CENTER_ACW", "L1"),
    L2_LEFT_ACW("L2_LEFT_ACW", "L2"),
    R1_CENTER_CW("R1_CENTER_CW", "R1"),
    R2_RIGHT_CW("R2_RIGHT_CW", "R2")
}

object TurnPacket {
    fun down(button: TurnButton): String = "TANKER_BTN,${button.wireName},DOWN,${System.currentTimeMillis()}\n"
    fun up(button: TurnButton): String = "TANKER_BTN,${button.wireName},UP,${System.currentTimeMillis()}\n"
}
