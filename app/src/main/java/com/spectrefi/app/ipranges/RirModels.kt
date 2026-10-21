package com.spectrefi.app.ui.ipranges

data class IpRange(
    val startIP: Long,
    val endIP: Long,
    val netname: String,
    val description: String,
    val country: String
)

enum class RIR(val id: Int, val whoisServer: String, val rdapUrl: String) {
    RIPE(1, "whois.ripe.net", "https://rdap.db.ripe.net/"),
    APNIC(2, "whois.apnic.net", "https://rdap.apnic.net/"),
    ARIN(3, "whois.arin.net", "https://rdap.arin.net/registry/"),
    AFRINIC(4, "whois.afrinic.net", "https://rdap.afrinic.net/rdap/"),
    LACNIC(5, "whois.lacnic.net", "https://rdap.lacnic.net/rdap/")
}

data class RdapResponse(
    val port43: String?,
    val startAddress: String?,
    val endAddress: String?,
    val name: String?,
    val country: String?,
    val description: List<String>?
)
