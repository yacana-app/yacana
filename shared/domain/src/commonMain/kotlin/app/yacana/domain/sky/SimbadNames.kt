// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sky

/**
 * A name as SIMBAD writes it, to search it when SIMBAD does not know it as typed. SIMBAD
 * names a star by the constellation's IAU abbreviation and the Greek letter's three letters, as
 * "* zet01 Ret": "zeta reticuli" is its stars named `* zet% Ret`, and "reticuli" those named `% Ret`,
 * which its table of names gives sorted by brightness. The typed name is searched as well, with
 * wildcards and in any case, as `*andromeda*`, for what is named in full, as the dwarf galaxies.
 */
object SimbadNames {
    /** What to ask SIMBAD for: [stars], a pattern of its names when [query] names a constellation, and [wildcard], the words as typed. */
    data class Queries(val stars: String?, val wildcard: String)

    /** The queries for [query]; null for a blank one. */
    fun queries(query: String): Queries? {
        val literal = fold(query).split(' ').filter { it.isNotEmpty() }
        if (literal.isEmpty()) return null
        val words = withAbbreviations(fold(query)).split(' ').filter { it.isNotEmpty() }
        val constellation = words.lastOrNull { it in ABBREVIATIONS }?.let(ABBREVIATIONS::getValue)
        val letter = words.firstNotNullOfOrNull { GREEK[it.takeWhile(Char::isLetter)] }
        val stars =
            constellation?.let {
                if (letter != null) "* $letter% $it" else "% $it"
            }
        return Queries(stars, "*" + literal.joinToString("*") + "*")
    }

    /** The folded [text] with each constellation's name, the longest first, as one word: its key in [ABBREVIATIONS]. */
    private fun withAbbreviations(text: String): String = NAMES.fold(" $text ") { folded, (name, key) ->
        folded.replace(" $name ", " $key ")
    }.trim()

    /** Each constellation's IAU abbreviation and its names: Latin, its genitive, and Spanish. */
    private val CONSTELLATIONS =
        listOf(
            "And" to "andromeda,andromedae,andrómeda",
            "Ant" to "antlia,antliae,máquina neumática",
            "Aps" to "apus,apodis,ave del paraíso",
            "Aqr" to "aquarius,aquarii,acuario",
            "Aql" to "aquila,aquilae,águila",
            "Ara" to "ara,arae,altar",
            "Ari" to "aries,arietis",
            "Aur" to "auriga,aurigae,cochero",
            "Boo" to "bootes,boötes,bootis,boyero",
            "Cae" to "caelum,caeli,cincel",
            "Cam" to "camelopardalis,jirafa",
            "Cnc" to "cancer,cancri,cáncer",
            "CVn" to "canes venatici,canum venaticorum,perros de caza",
            "CMa" to "canis major,canis majoris,can mayor",
            "CMi" to "canis minor,canis minoris,can menor",
            "Cap" to "capricornus,capricorni,capricornio",
            "Car" to "carina,carinae,quilla",
            "Cas" to "cassiopeia,cassiopeiae,casiopea",
            "Cen" to "centaurus,centauri,centauro",
            "Cep" to "cepheus,cephei,cefeo",
            "Cet" to "cetus,ceti,ballena",
            "Cha" to "chamaeleon,chamaeleontis,camaleón",
            "Cir" to "circinus,circini,compás",
            "Col" to "columba,columbae,paloma",
            "Com" to "coma berenices,comae berenices,cabellera de berenice",
            "CrA" to "corona australis,coronae australis,corona austral",
            "CrB" to "corona borealis,coronae borealis,corona boreal",
            "Crv" to "corvus,corvi,cuervo",
            "Crt" to "crater,crateris,copa",
            "Cru" to "crux,crucis,cruz del sur",
            "Cyg" to "cygnus,cygni,cisne",
            "Del" to "delphinus,delphini,delfín",
            "Dor" to "dorado,doradus",
            "Dra" to "draco,draconis,dragón",
            "Equ" to "equuleus,equulei,caballito",
            "Eri" to "eridanus,eridani,erídano",
            "For" to "fornax,fornacis,horno",
            "Gem" to "gemini,geminorum,géminis",
            "Gru" to "grus,gruis,grulla",
            "Her" to "hercules,herculis,hércules",
            "Hor" to "horologium,horologii,reloj",
            "Hya" to "hydra,hydrae,hidra",
            "Hyi" to "hydrus,hydri,hidra macho",
            "Ind" to "indus,indi,indio",
            "Lac" to "lacerta,lacertae,lagarto",
            "Leo" to "leo,leonis",
            "LMi" to "leo minor,leonis minoris,león menor",
            "Lep" to "lepus,leporis,liebre",
            "Lib" to "libra,librae",
            "Lup" to "lupus,lupi,lobo",
            "Lyn" to "lynx,lyncis,lince",
            "Lyr" to "lyra,lyrae,lira",
            "Men" to "mensa,mensae,mesa",
            "Mic" to "microscopium,microscopii,microscopio",
            "Mon" to "monoceros,monocerotis,unicornio",
            "Mus" to "musca,muscae,mosca",
            "Nor" to "norma,normae,escuadra",
            "Oct" to "octans,octantis,octante",
            "Oph" to "ophiuchus,ophiuchi,ofiuco",
            "Ori" to "orion,orionis,orión",
            "Pav" to "pavo,pavonis",
            "Peg" to "pegasus,pegasi,pegaso",
            "Per" to "perseus,persei,perseo",
            "Phe" to "phoenix,phoenicis,fénix",
            "Pic" to "pictor,pictoris,pintor",
            "Psc" to "pisces,piscium,piscis",
            "PsA" to "piscis austrinus,piscis austrini,pez austral",
            "Pup" to "puppis,popa",
            "Pyx" to "pyxis,pyxidis,brújula",
            "Ret" to "reticulum,reticuli,retículo",
            "Sge" to "sagitta,sagittae,flecha",
            "Sgr" to "sagittarius,sagittarii,sagitario",
            "Sco" to "scorpius,scorpii,escorpio,escorpión",
            "Scl" to "sculptor,sculptoris,escultor",
            "Sct" to "scutum,scuti,escudo",
            "Ser" to "serpens,serpentis,serpiente",
            "Sex" to "sextans,sextantis,sextante",
            "Tau" to "taurus,tauri,tauro",
            "Tel" to "telescopium,telescopii,telescopio",
            "Tri" to "triangulum,trianguli,triángulo",
            "TrA" to "triangulum australe,trianguli australis,triángulo austral",
            "Tuc" to "tucana,tucanae,tucán",
            "UMa" to "ursa major,ursae majoris,osa mayor",
            "UMi" to "ursa minor,ursae minoris,osa menor",
            "Vel" to "vela,velorum",
            "Vir" to "virgo,virginis",
            "Vol" to "volans,volantis,pez volador",
            "Vul" to "vulpecula,vulpeculae,zorra",
        )

    /** Each constellation's key, its abbreviation folded with a mark that no name has, to its abbreviation. */
    private val ABBREVIATIONS = CONSTELLATIONS.associate { (abbreviation, _) -> "@${abbreviation.lowercase()}" to abbreviation }

    /** Each folded name and its constellation's key, the longest first, so that "osa mayor" goes before "osa". */
    private val NAMES =
        CONSTELLATIONS
            .flatMap { (abbreviation, names) -> names.split(',').map { fold(it) to "@${abbreviation.lowercase()}" } }
            .sortedByDescending { it.first.length }

    /** The Greek letters' names, in Spanish and English, as SIMBAD writes them. */
    private val GREEK =
        mapOf(
            "alf" to "alfa,alpha", "bet" to "beta", "gam" to "gamma", "del" to "delta", "eps" to "epsilon",
            "zet" to "zeta,dseta", "eta" to "eta", "tet" to "theta", "iot" to "iota", "kap" to "kappa,kapa",
            "lam" to "lambda", "mu" to "mu,my", "nu" to "nu,ny", "ksi" to "xi,ksi", "omi" to "omicron",
            "pi" to "pi", "rho" to "rho,ro", "sig" to "sigma", "tau" to "tau", "ups" to "upsilon,ypsilon,ipsilon",
            "phi" to "phi,fi", "chi" to "chi,ji", "psi" to "psi", "ome" to "omega",
        ).flatMap { (code, names) -> names.split(',').map { fold(it) to code } }.toMap()
}
