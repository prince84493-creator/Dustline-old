import re, sys
src = open(sys.argv[1], encoding='utf-8').read().split('\n')
def grab(sig):
    for i, l in enumerate(src):
        if sig in l and l.startswith('    ') and not l.startswith('     '):
            depth = 0; out = []
            for j in range(i, len(src)):
                out.append(src[j]); depth += src[j].count('{') - src[j].count('}')
                if depth == 0: return '\n'.join(out)
    raise SystemExit('not found ' + sig)
fn = [grab(s) for s in ('fun landSurface(', 'fun freeLandingSpot(', 'fun houseEscape(', 'private fun surfH(', 'fun footY(')]
head = '''
import java.nio.*
import java.io.File
class Box(@JvmField val minX: Float, @JvmField val maxX: Float, @JvmField val minY: Float, @JvmField val maxY: Float, @JvmField val minZ: Float, @JvmField val maxZ: Float)
class House(@JvmField val minX: Float, @JvmField val maxX: Float, @JvmField val minZ: Float, @JvmField val maxZ: Float, @JvmField val y0: Float, @JvmField val surf: Array<FloatArray>)
class MapStub {
    val houses = ArrayList<House>()
    val boxes = ArrayList<Box>()
    fun ground(x: Float, z: Float): Float = 2.4f
    fun terrainBlocked(x: Float, z: Float, r: Float, y: Float = 0f, swim: Boolean = false): Boolean = false
    fun blocked(x: Float, z: Float, r: Float, y: Float): Boolean {
        for (b in boxes) if (x > b.minX - r && x < b.maxX + r && z > b.minZ - r && z < b.maxZ + r && y < b.maxY - 0.3f && y + 1.7f > b.minY) return true
        return false
    }
    fun load(path: String) {
        val bb = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4); bb.get(magic); val nh = bb.getInt()
        for (h in 0 until nh) {
            val minX = bb.getFloat(); val maxX = bb.getFloat(); val minZ = bb.getFloat(); val maxZ = bb.getFloat(); val y0 = bb.getFloat(); bb.getFloat(); bb.getFloat()
            val nv = bb.getInt(); val ni = bb.getInt(); val nb = bb.getInt(); val ns = bb.getInt(); val nl = bb.getInt()
            for (q in 0 until nv * 11) bb.getFloat(); for (q in 0 until ni) bb.getInt()
            for (q in 0 until nb) { val a = bb.getFloat(); val b = bb.getFloat(); val c = bb.getFloat(); val d = bb.getFloat(); val e = bb.getFloat(); val f = bb.getFloat(); boxes.add(Box(a, b, c, d, e, f)) }
            val sf = Array(ns) { FloatArray(18) { bb.getFloat() } }
            for (q in 0 until nl * 3) bb.getFloat()
            houses.add(House(minX, maxX, minZ, maxZ, y0, sf))
        }
    }
'''
test = open('test_body.kt').read()
open('Harness.kt', 'w').write(head + '\n'.join(fn) + '\n' + test)
