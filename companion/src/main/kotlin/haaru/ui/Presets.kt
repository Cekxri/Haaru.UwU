/* Preset values adapted from Haaru, Apache-2.0.
 * Source: a5533348/Haaru@6c05f224dc802d0df952db93a6d2852a91c9edb0.
 * All 28 countries and all 74 carriers retained; visible Chinese labels localised.
 * Carrier displayName values are unchanged from upstream.
 */
package haaru.ui

data class CountryPreset(val code: String, val name: String)
data class CarrierPreset(val id: String, val name: String, val displayName: String, val region: String)

object HaaruPresets {
    val countries = listOf(
        CountryPreset("CN", "中國大陸"),
        CountryPreset("HK", "香港"),
        CountryPreset("MO", "澳門"),
        CountryPreset("TW", "台灣"),
        CountryPreset("JP", "日本"),
        CountryPreset("KR", "韓國"),
        CountryPreset("US", "美國"),
        CountryPreset("GB", "英國"),
        CountryPreset("DE", "德國"),
        CountryPreset("FR", "法國"),
        CountryPreset("IT", "義大利"),
        CountryPreset("ES", "西班牙"),
        CountryPreset("PT", "葡萄牙"),
        CountryPreset("RU", "俄羅斯"),
        CountryPreset("IN", "印度"),
        CountryPreset("AU", "澳洲"),
        CountryPreset("NZ", "紐西蘭"),
        CountryPreset("SG", "新加坡"),
        CountryPreset("MY", "馬來西亞"),
        CountryPreset("TH", "泰國"),
        CountryPreset("VN", "越南"),
        CountryPreset("ID", "印尼"),
        CountryPreset("PH", "菲律賓"),
        CountryPreset("CA", "加拿大"),
        CountryPreset("MX", "墨西哥"),
        CountryPreset("BR", "巴西"),
        CountryPreset("AR", "阿根廷"),
        CountryPreset("ZA", "南非")
    )
    val carriers = listOf(
        CarrierPreset("CN:China Mobile", "中國移動", "China Mobile", "CN"),
        CarrierPreset("CN:China Unicom", "中國聯通", "China Unicom", "CN"),
        CarrierPreset("CN:China Telecom", "中國電信", "China Telecom", "CN"),
        CarrierPreset("HK:CMHK", "中國移動香港", "CMHK", "HK"),
        CarrierPreset("HK:HKT", "香港電訊", "HKT", "HK"),
        CarrierPreset("HK:3HK", "3香港", "3HK", "HK"),
        CarrierPreset("HK:SmarTone", "SmarTone", "SmarTone", "HK"),
        CarrierPreset("MO:CTM", "澳門電訊", "CTM", "MO"),
        CarrierPreset("MO:3 Macau", "3澳門", "3 Macau", "MO"),
        CarrierPreset("TW:Chunghwa Telecom", "中華電信", "Chunghwa Telecom", "TW"),
        CarrierPreset("TW:Taiwan Mobile", "台灣大哥大", "Taiwan Mobile", "TW"),
        CarrierPreset("TW:FarEasTone", "遠傳電信", "FarEasTone", "TW"),
        CarrierPreset("JP:NTT docomo", "NTT docomo", "NTT docomo", "JP"),
        CarrierPreset("JP:au by KDDI", "au", "au by KDDI", "JP"),
        CarrierPreset("JP:Softbank", "Softbank", "Softbank", "JP"),
        CarrierPreset("JP:Rakuten Mobile", "Rakuten", "Rakuten Mobile", "JP"),
        CarrierPreset("KR:SK Telecom", "SK Telecom", "SK Telecom", "KR"),
        CarrierPreset("KR:KT Corporation", "KT", "KT Corporation", "KR"),
        CarrierPreset("KR:LG U+", "LG U+", "LG U+", "KR"),
        CarrierPreset("US:AT&T", "AT&T", "AT&T", "US"),
        CarrierPreset("US:T-Mobile USA", "T-Mobile", "T-Mobile USA", "US"),
        CarrierPreset("US:Verizon", "Verizon", "Verizon", "US"),
        CarrierPreset("US:Sprint", "Sprint", "Sprint", "US"),
        CarrierPreset("GB:EE", "EE", "EE", "GB"),
        CarrierPreset("GB:O2 UK", "O2", "O2 UK", "GB"),
        CarrierPreset("GB:Three UK", "Three", "Three UK", "GB"),
        CarrierPreset("GB:Vodafone UK", "Vodafone", "Vodafone UK", "GB"),
        CarrierPreset("SG:Singtel", "Singtel", "Singtel", "SG"),
        CarrierPreset("SG:StarHub", "StarHub", "StarHub", "SG"),
        CarrierPreset("SG:M1", "M1", "M1", "SG"),
        CarrierPreset("MY:Maxis", "Maxis", "Maxis", "MY"),
        CarrierPreset("MY:Celcom", "Celcom", "Celcom", "MY"),
        CarrierPreset("MY:Digi", "Digi", "Digi", "MY"),
        CarrierPreset("MY:U Mobile", "U Mobile", "U Mobile", "MY"),
        CarrierPreset("TH:AIS", "AIS", "AIS", "TH"),
        CarrierPreset("TH:DTAC", "DTAC", "DTAC", "TH"),
        CarrierPreset("TH:True Move H", "True Move H", "True Move H", "TH"),
        CarrierPreset("VN:Viettel Mobile", "Viettel", "Viettel Mobile", "VN"),
        CarrierPreset("VN:Vinaphone", "Vinaphone", "Vinaphone", "VN"),
        CarrierPreset("VN:Mobifone", "Mobifone", "Mobifone", "VN"),
        CarrierPreset("ID:Telkomsel", "Telkomsel", "Telkomsel", "ID"),
        CarrierPreset("ID:Indosat Ooredoo", "Indosat", "Indosat Ooredoo", "ID"),
        CarrierPreset("ID:XL Axiata", "XL Axiata", "XL Axiata", "ID"),
        CarrierPreset("PH:Globe Telecom", "Globe", "Globe Telecom", "PH"),
        CarrierPreset("PH:Smart Communications", "Smart", "Smart Communications", "PH"),
        CarrierPreset("PH:DITO Telecommunity", "DITO", "DITO Telecommunity", "PH"),
        CarrierPreset("IN:Reliance Jio", "Jio", "Reliance Jio", "IN"),
        CarrierPreset("IN:Bharti Airtel", "Airtel", "Bharti Airtel", "IN"),
        CarrierPreset("IN:Vodafone Idea", "Vi", "Vodafone Idea", "IN"),
        CarrierPreset("AU:Telstra", "Telstra", "Telstra", "AU"),
        CarrierPreset("AU:Optus", "Optus", "Optus", "AU"),
        CarrierPreset("AU:Vodafone AU", "Vodafone", "Vodafone AU", "AU"),
        CarrierPreset("CA:Bell Mobility", "Bell", "Bell Mobility", "CA"),
        CarrierPreset("CA:Rogers Wireless", "Rogers", "Rogers Wireless", "CA"),
        CarrierPreset("CA:Telus Mobility", "Telus", "Telus Mobility", "CA"),
        CarrierPreset("DE:T-Mobile DE", "Telekom", "T-Mobile DE", "DE"),
        CarrierPreset("DE:Vodafone DE", "Vodafone", "Vodafone DE", "DE"),
        CarrierPreset("DE:O2 DE", "O2", "O2 DE", "DE"),
        CarrierPreset("FR:Orange FR", "Orange", "Orange FR", "FR"),
        CarrierPreset("FR:SFR", "SFR", "SFR", "FR"),
        CarrierPreset("FR:Free Mobile", "Free", "Free Mobile", "FR"),
        CarrierPreset("FR:Bouygues Telecom", "Bouygues", "Bouygues Telecom", "FR"),
        CarrierPreset("IT:Telecom Italia", "TIM", "Telecom Italia", "IT"),
        CarrierPreset("IT:Vodafone IT", "Vodafone", "Vodafone IT", "IT"),
        CarrierPreset("IT:Wind Tre", "Wind Tre", "Wind Tre", "IT"),
        CarrierPreset("ES:Movistar", "Movistar", "Movistar", "ES"),
        CarrierPreset("ES:Vodafone ES", "Vodafone", "Vodafone ES", "ES"),
        CarrierPreset("ES:Orange ES", "Orange", "Orange ES", "ES"),
        CarrierPreset("RU:MTS", "MTS", "MTS", "RU"),
        CarrierPreset("RU:MegaFon", "MegaFon", "MegaFon", "RU"),
        CarrierPreset("RU:Beeline", "Beeline", "Beeline", "RU"),
        CarrierPreset("BR:Vivo", "Vivo", "Vivo", "BR"),
        CarrierPreset("BR:Claro", "Claro", "Claro", "BR"),
        CarrierPreset("BR:TIM Brasil", "TIM", "TIM Brasil", "BR")
    )
    fun countryLabel(code: String): String = countries.firstOrNull { it.code == code }?.let { "${it.name} · ${it.code}" } ?: code
    fun carrierLabel(carrier: CarrierPreset): String {
        val region = countries.firstOrNull { it.code == carrier.region }?.name ?: carrier.region
        return "${carrier.name} · ${carrier.displayName} · ${region}"
    }
}

