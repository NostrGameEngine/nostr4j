/******/ var __webpack_modules__ = ({

/***/ 526
(__unused_webpack_module, exports) {



exports.byteLength = byteLength
exports.toByteArray = toByteArray
exports.fromByteArray = fromByteArray

var lookup = []
var revLookup = []
var Arr = typeof Uint8Array !== 'undefined' ? Uint8Array : Array

var code = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
for (var i = 0, len = code.length; i < len; ++i) {
  lookup[i] = code[i]
  revLookup[code.charCodeAt(i)] = i
}

// Support decoding URL-safe base64 strings, as Node.js does.
// See: https://en.wikipedia.org/wiki/Base64#URL_applications
revLookup['-'.charCodeAt(0)] = 62
revLookup['_'.charCodeAt(0)] = 63

function getLens (b64) {
  var len = b64.length

  if (len % 4 > 0) {
    throw new Error('Invalid string. Length must be a multiple of 4')
  }

  // Trim off extra bytes after placeholder bytes are found
  // See: https://github.com/beatgammit/base64-js/issues/42
  var validLen = b64.indexOf('=')
  if (validLen === -1) validLen = len

  var placeHoldersLen = validLen === len
    ? 0
    : 4 - (validLen % 4)

  return [validLen, placeHoldersLen]
}

// base64 is 4/3 + up to two characters of the original data
function byteLength (b64) {
  var lens = getLens(b64)
  var validLen = lens[0]
  var placeHoldersLen = lens[1]
  return ((validLen + placeHoldersLen) * 3 / 4) - placeHoldersLen
}

function _byteLength (b64, validLen, placeHoldersLen) {
  return ((validLen + placeHoldersLen) * 3 / 4) - placeHoldersLen
}

function toByteArray (b64) {
  var tmp
  var lens = getLens(b64)
  var validLen = lens[0]
  var placeHoldersLen = lens[1]

  var arr = new Arr(_byteLength(b64, validLen, placeHoldersLen))

  var curByte = 0

  // if there are placeholders, only get up to the last complete 4 chars
  var len = placeHoldersLen > 0
    ? validLen - 4
    : validLen

  var i
  for (i = 0; i < len; i += 4) {
    tmp =
      (revLookup[b64.charCodeAt(i)] << 18) |
      (revLookup[b64.charCodeAt(i + 1)] << 12) |
      (revLookup[b64.charCodeAt(i + 2)] << 6) |
      revLookup[b64.charCodeAt(i + 3)]
    arr[curByte++] = (tmp >> 16) & 0xFF
    arr[curByte++] = (tmp >> 8) & 0xFF
    arr[curByte++] = tmp & 0xFF
  }

  if (placeHoldersLen === 2) {
    tmp =
      (revLookup[b64.charCodeAt(i)] << 2) |
      (revLookup[b64.charCodeAt(i + 1)] >> 4)
    arr[curByte++] = tmp & 0xFF
  }

  if (placeHoldersLen === 1) {
    tmp =
      (revLookup[b64.charCodeAt(i)] << 10) |
      (revLookup[b64.charCodeAt(i + 1)] << 4) |
      (revLookup[b64.charCodeAt(i + 2)] >> 2)
    arr[curByte++] = (tmp >> 8) & 0xFF
    arr[curByte++] = tmp & 0xFF
  }

  return arr
}

function tripletToBase64 (num) {
  return lookup[num >> 18 & 0x3F] +
    lookup[num >> 12 & 0x3F] +
    lookup[num >> 6 & 0x3F] +
    lookup[num & 0x3F]
}

function encodeChunk (uint8, start, end) {
  var tmp
  var output = []
  for (var i = start; i < end; i += 3) {
    tmp =
      ((uint8[i] << 16) & 0xFF0000) +
      ((uint8[i + 1] << 8) & 0xFF00) +
      (uint8[i + 2] & 0xFF)
    output.push(tripletToBase64(tmp))
  }
  return output.join('')
}

function fromByteArray (uint8) {
  var tmp
  var len = uint8.length
  var extraBytes = len % 3 // if we have 1 byte left, pad 2 bytes
  var parts = []
  var maxChunkLength = 16383 // must be multiple of 3

  // go through the array every three bytes, we'll deal with trailing stuff later
  for (var i = 0, len2 = len - extraBytes; i < len2; i += maxChunkLength) {
    parts.push(encodeChunk(uint8, i, (i + maxChunkLength) > len2 ? len2 : (i + maxChunkLength)))
  }

  // pad the end with zeros, but make sure to not forget the extra bytes
  if (extraBytes === 1) {
    tmp = uint8[len - 1]
    parts.push(
      lookup[tmp >> 2] +
      lookup[(tmp << 4) & 0x3F] +
      '=='
    )
  } else if (extraBytes === 2) {
    tmp = (uint8[len - 2] << 8) + uint8[len - 1]
    parts.push(
      lookup[tmp >> 10] +
      lookup[(tmp >> 4) & 0x3F] +
      lookup[(tmp << 2) & 0x3F] +
      '='
    )
  }

  return parts.join('')
}


/***/ },

/***/ 287
(__unused_webpack_module, exports, __webpack_require__) {

var __webpack_unused_export__;
/*!
 * The buffer module from node.js, for the browser.
 *
 * @author   Feross Aboukhadijeh <https://feross.org>
 * @license  MIT
 */
/* eslint-disable no-proto */



const base64 = __webpack_require__(526)
const ieee754 = __webpack_require__(251)
const customInspectSymbol =
  (typeof Symbol === 'function' && typeof Symbol['for'] === 'function') // eslint-disable-line dot-notation
    ? Symbol['for']('nodejs.util.inspect.custom') // eslint-disable-line dot-notation
    : null

exports.Buffer = Buffer
__webpack_unused_export__ = SlowBuffer
exports.INSPECT_MAX_BYTES = 50

const K_MAX_LENGTH = 0x7fffffff
__webpack_unused_export__ = K_MAX_LENGTH

/**
 * If `Buffer.TYPED_ARRAY_SUPPORT`:
 *   === true    Use Uint8Array implementation (fastest)
 *   === false   Print warning and recommend using `buffer` v4.x which has an Object
 *               implementation (most compatible, even IE6)
 *
 * Browsers that support typed arrays are IE 10+, Firefox 4+, Chrome 7+, Safari 5.1+,
 * Opera 11.6+, iOS 4.2+.
 *
 * We report that the browser does not support typed arrays if the are not subclassable
 * using __proto__. Firefox 4-29 lacks support for adding new properties to `Uint8Array`
 * (See: https://bugzilla.mozilla.org/show_bug.cgi?id=695438). IE 10 lacks support
 * for __proto__ and has a buggy typed array implementation.
 */
Buffer.TYPED_ARRAY_SUPPORT = typedArraySupport()

if (!Buffer.TYPED_ARRAY_SUPPORT && typeof console !== 'undefined' &&
    typeof console.error === 'function') {
  console.error(
    'This browser lacks typed array (Uint8Array) support which is required by ' +
    '`buffer` v5.x. Use `buffer` v4.x if you require old browser support.'
  )
}

function typedArraySupport () {
  // Can typed array instances can be augmented?
  try {
    const arr = new Uint8Array(1)
    const proto = { foo: function () { return 42 } }
    Object.setPrototypeOf(proto, Uint8Array.prototype)
    Object.setPrototypeOf(arr, proto)
    return arr.foo() === 42
  } catch (e) {
    return false
  }
}

Object.defineProperty(Buffer.prototype, 'parent', {
  enumerable: true,
  get: function () {
    if (!Buffer.isBuffer(this)) return undefined
    return this.buffer
  }
})

Object.defineProperty(Buffer.prototype, 'offset', {
  enumerable: true,
  get: function () {
    if (!Buffer.isBuffer(this)) return undefined
    return this.byteOffset
  }
})

function createBuffer (length) {
  if (length > K_MAX_LENGTH) {
    throw new RangeError('The value "' + length + '" is invalid for option "size"')
  }
  // Return an augmented `Uint8Array` instance
  const buf = new Uint8Array(length)
  Object.setPrototypeOf(buf, Buffer.prototype)
  return buf
}

/**
 * The Buffer constructor returns instances of `Uint8Array` that have their
 * prototype changed to `Buffer.prototype`. Furthermore, `Buffer` is a subclass of
 * `Uint8Array`, so the returned instances will have all the node `Buffer` methods
 * and the `Uint8Array` methods. Square bracket notation works as expected -- it
 * returns a single octet.
 *
 * The `Uint8Array` prototype remains unmodified.
 */

function Buffer (arg, encodingOrOffset, length) {
  // Common case.
  if (typeof arg === 'number') {
    if (typeof encodingOrOffset === 'string') {
      throw new TypeError(
        'The "string" argument must be of type string. Received type number'
      )
    }
    return allocUnsafe(arg)
  }
  return from(arg, encodingOrOffset, length)
}

Buffer.poolSize = 8192 // not used by this implementation

function from (value, encodingOrOffset, length) {
  if (typeof value === 'string') {
    return fromString(value, encodingOrOffset)
  }

  if (ArrayBuffer.isView(value)) {
    return fromArrayView(value)
  }

  if (value == null) {
    throw new TypeError(
      'The first argument must be one of type string, Buffer, ArrayBuffer, Array, ' +
      'or Array-like Object. Received type ' + (typeof value)
    )
  }

  if (isInstance(value, ArrayBuffer) ||
      (value && isInstance(value.buffer, ArrayBuffer))) {
    return fromArrayBuffer(value, encodingOrOffset, length)
  }

  if (typeof SharedArrayBuffer !== 'undefined' &&
      (isInstance(value, SharedArrayBuffer) ||
      (value && isInstance(value.buffer, SharedArrayBuffer)))) {
    return fromArrayBuffer(value, encodingOrOffset, length)
  }

  if (typeof value === 'number') {
    throw new TypeError(
      'The "value" argument must not be of type number. Received type number'
    )
  }

  const valueOf = value.valueOf && value.valueOf()
  if (valueOf != null && valueOf !== value) {
    return Buffer.from(valueOf, encodingOrOffset, length)
  }

  const b = fromObject(value)
  if (b) return b

  if (typeof Symbol !== 'undefined' && Symbol.toPrimitive != null &&
      typeof value[Symbol.toPrimitive] === 'function') {
    return Buffer.from(value[Symbol.toPrimitive]('string'), encodingOrOffset, length)
  }

  throw new TypeError(
    'The first argument must be one of type string, Buffer, ArrayBuffer, Array, ' +
    'or Array-like Object. Received type ' + (typeof value)
  )
}

/**
 * Functionally equivalent to Buffer(arg, encoding) but throws a TypeError
 * if value is a number.
 * Buffer.from(str[, encoding])
 * Buffer.from(array)
 * Buffer.from(buffer)
 * Buffer.from(arrayBuffer[, byteOffset[, length]])
 **/
Buffer.from = function (value, encodingOrOffset, length) {
  return from(value, encodingOrOffset, length)
}

// Note: Change prototype *after* Buffer.from is defined to workaround Chrome bug:
// https://github.com/feross/buffer/pull/148
Object.setPrototypeOf(Buffer.prototype, Uint8Array.prototype)
Object.setPrototypeOf(Buffer, Uint8Array)

function assertSize (size) {
  if (typeof size !== 'number') {
    throw new TypeError('"size" argument must be of type number')
  } else if (size < 0) {
    throw new RangeError('The value "' + size + '" is invalid for option "size"')
  }
}

function alloc (size, fill, encoding) {
  assertSize(size)
  if (size <= 0) {
    return createBuffer(size)
  }
  if (fill !== undefined) {
    // Only pay attention to encoding if it's a string. This
    // prevents accidentally sending in a number that would
    // be interpreted as a start offset.
    return typeof encoding === 'string'
      ? createBuffer(size).fill(fill, encoding)
      : createBuffer(size).fill(fill)
  }
  return createBuffer(size)
}

/**
 * Creates a new filled Buffer instance.
 * alloc(size[, fill[, encoding]])
 **/
Buffer.alloc = function (size, fill, encoding) {
  return alloc(size, fill, encoding)
}

function allocUnsafe (size) {
  assertSize(size)
  return createBuffer(size < 0 ? 0 : checked(size) | 0)
}

/**
 * Equivalent to Buffer(num), by default creates a non-zero-filled Buffer instance.
 * */
Buffer.allocUnsafe = function (size) {
  return allocUnsafe(size)
}
/**
 * Equivalent to SlowBuffer(num), by default creates a non-zero-filled Buffer instance.
 */
Buffer.allocUnsafeSlow = function (size) {
  return allocUnsafe(size)
}

function fromString (string, encoding) {
  if (typeof encoding !== 'string' || encoding === '') {
    encoding = 'utf8'
  }

  if (!Buffer.isEncoding(encoding)) {
    throw new TypeError('Unknown encoding: ' + encoding)
  }

  const length = byteLength(string, encoding) | 0
  let buf = createBuffer(length)

  const actual = buf.write(string, encoding)

  if (actual !== length) {
    // Writing a hex string, for example, that contains invalid characters will
    // cause everything after the first invalid character to be ignored. (e.g.
    // 'abxxcd' will be treated as 'ab')
    buf = buf.slice(0, actual)
  }

  return buf
}

function fromArrayLike (array) {
  const length = array.length < 0 ? 0 : checked(array.length) | 0
  const buf = createBuffer(length)
  for (let i = 0; i < length; i += 1) {
    buf[i] = array[i] & 255
  }
  return buf
}

function fromArrayView (arrayView) {
  if (isInstance(arrayView, Uint8Array)) {
    const copy = new Uint8Array(arrayView)
    return fromArrayBuffer(copy.buffer, copy.byteOffset, copy.byteLength)
  }
  return fromArrayLike(arrayView)
}

function fromArrayBuffer (array, byteOffset, length) {
  if (byteOffset < 0 || array.byteLength < byteOffset) {
    throw new RangeError('"offset" is outside of buffer bounds')
  }

  if (array.byteLength < byteOffset + (length || 0)) {
    throw new RangeError('"length" is outside of buffer bounds')
  }

  let buf
  if (byteOffset === undefined && length === undefined) {
    buf = new Uint8Array(array)
  } else if (length === undefined) {
    buf = new Uint8Array(array, byteOffset)
  } else {
    buf = new Uint8Array(array, byteOffset, length)
  }

  // Return an augmented `Uint8Array` instance
  Object.setPrototypeOf(buf, Buffer.prototype)

  return buf
}

function fromObject (obj) {
  if (Buffer.isBuffer(obj)) {
    const len = checked(obj.length) | 0
    const buf = createBuffer(len)

    if (buf.length === 0) {
      return buf
    }

    obj.copy(buf, 0, 0, len)
    return buf
  }

  if (obj.length !== undefined) {
    if (typeof obj.length !== 'number' || numberIsNaN(obj.length)) {
      return createBuffer(0)
    }
    return fromArrayLike(obj)
  }

  if (obj.type === 'Buffer' && Array.isArray(obj.data)) {
    return fromArrayLike(obj.data)
  }
}

function checked (length) {
  // Note: cannot use `length < K_MAX_LENGTH` here because that fails when
  // length is NaN (which is otherwise coerced to zero.)
  if (length >= K_MAX_LENGTH) {
    throw new RangeError('Attempt to allocate Buffer larger than maximum ' +
                         'size: 0x' + K_MAX_LENGTH.toString(16) + ' bytes')
  }
  return length | 0
}

function SlowBuffer (length) {
  if (+length != length) { // eslint-disable-line eqeqeq
    length = 0
  }
  return Buffer.alloc(+length)
}

Buffer.isBuffer = function isBuffer (b) {
  return b != null && b._isBuffer === true &&
    b !== Buffer.prototype // so Buffer.isBuffer(Buffer.prototype) will be false
}

Buffer.compare = function compare (a, b) {
  if (isInstance(a, Uint8Array)) a = Buffer.from(a, a.offset, a.byteLength)
  if (isInstance(b, Uint8Array)) b = Buffer.from(b, b.offset, b.byteLength)
  if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b)) {
    throw new TypeError(
      'The "buf1", "buf2" arguments must be one of type Buffer or Uint8Array'
    )
  }

  if (a === b) return 0

  let x = a.length
  let y = b.length

  for (let i = 0, len = Math.min(x, y); i < len; ++i) {
    if (a[i] !== b[i]) {
      x = a[i]
      y = b[i]
      break
    }
  }

  if (x < y) return -1
  if (y < x) return 1
  return 0
}

Buffer.isEncoding = function isEncoding (encoding) {
  switch (String(encoding).toLowerCase()) {
    case 'hex':
    case 'utf8':
    case 'utf-8':
    case 'ascii':
    case 'latin1':
    case 'binary':
    case 'base64':
    case 'ucs2':
    case 'ucs-2':
    case 'utf16le':
    case 'utf-16le':
      return true
    default:
      return false
  }
}

Buffer.concat = function concat (list, length) {
  if (!Array.isArray(list)) {
    throw new TypeError('"list" argument must be an Array of Buffers')
  }

  if (list.length === 0) {
    return Buffer.alloc(0)
  }

  let i
  if (length === undefined) {
    length = 0
    for (i = 0; i < list.length; ++i) {
      length += list[i].length
    }
  }

  const buffer = Buffer.allocUnsafe(length)
  let pos = 0
  for (i = 0; i < list.length; ++i) {
    let buf = list[i]
    if (isInstance(buf, Uint8Array)) {
      if (pos + buf.length > buffer.length) {
        if (!Buffer.isBuffer(buf)) buf = Buffer.from(buf)
        buf.copy(buffer, pos)
      } else {
        Uint8Array.prototype.set.call(
          buffer,
          buf,
          pos
        )
      }
    } else if (!Buffer.isBuffer(buf)) {
      throw new TypeError('"list" argument must be an Array of Buffers')
    } else {
      buf.copy(buffer, pos)
    }
    pos += buf.length
  }
  return buffer
}

function byteLength (string, encoding) {
  if (Buffer.isBuffer(string)) {
    return string.length
  }
  if (ArrayBuffer.isView(string) || isInstance(string, ArrayBuffer)) {
    return string.byteLength
  }
  if (typeof string !== 'string') {
    throw new TypeError(
      'The "string" argument must be one of type string, Buffer, or ArrayBuffer. ' +
      'Received type ' + typeof string
    )
  }

  const len = string.length
  const mustMatch = (arguments.length > 2 && arguments[2] === true)
  if (!mustMatch && len === 0) return 0

  // Use a for loop to avoid recursion
  let loweredCase = false
  for (;;) {
    switch (encoding) {
      case 'ascii':
      case 'latin1':
      case 'binary':
        return len
      case 'utf8':
      case 'utf-8':
        return utf8ToBytes(string).length
      case 'ucs2':
      case 'ucs-2':
      case 'utf16le':
      case 'utf-16le':
        return len * 2
      case 'hex':
        return len >>> 1
      case 'base64':
        return base64ToBytes(string).length
      default:
        if (loweredCase) {
          return mustMatch ? -1 : utf8ToBytes(string).length // assume utf8
        }
        encoding = ('' + encoding).toLowerCase()
        loweredCase = true
    }
  }
}
Buffer.byteLength = byteLength

function slowToString (encoding, start, end) {
  let loweredCase = false

  // No need to verify that "this.length <= MAX_UINT32" since it's a read-only
  // property of a typed array.

  // This behaves neither like String nor Uint8Array in that we set start/end
  // to their upper/lower bounds if the value passed is out of range.
  // undefined is handled specially as per ECMA-262 6th Edition,
  // Section 13.3.3.7 Runtime Semantics: KeyedBindingInitialization.
  if (start === undefined || start < 0) {
    start = 0
  }
  // Return early if start > this.length. Done here to prevent potential uint32
  // coercion fail below.
  if (start > this.length) {
    return ''
  }

  if (end === undefined || end > this.length) {
    end = this.length
  }

  if (end <= 0) {
    return ''
  }

  // Force coercion to uint32. This will also coerce falsey/NaN values to 0.
  end >>>= 0
  start >>>= 0

  if (end <= start) {
    return ''
  }

  if (!encoding) encoding = 'utf8'

  while (true) {
    switch (encoding) {
      case 'hex':
        return hexSlice(this, start, end)

      case 'utf8':
      case 'utf-8':
        return utf8Slice(this, start, end)

      case 'ascii':
        return asciiSlice(this, start, end)

      case 'latin1':
      case 'binary':
        return latin1Slice(this, start, end)

      case 'base64':
        return base64Slice(this, start, end)

      case 'ucs2':
      case 'ucs-2':
      case 'utf16le':
      case 'utf-16le':
        return utf16leSlice(this, start, end)

      default:
        if (loweredCase) throw new TypeError('Unknown encoding: ' + encoding)
        encoding = (encoding + '').toLowerCase()
        loweredCase = true
    }
  }
}

// This property is used by `Buffer.isBuffer` (and the `is-buffer` npm package)
// to detect a Buffer instance. It's not possible to use `instanceof Buffer`
// reliably in a browserify context because there could be multiple different
// copies of the 'buffer' package in use. This method works even for Buffer
// instances that were created from another copy of the `buffer` package.
// See: https://github.com/feross/buffer/issues/154
Buffer.prototype._isBuffer = true

function swap (b, n, m) {
  const i = b[n]
  b[n] = b[m]
  b[m] = i
}

Buffer.prototype.swap16 = function swap16 () {
  const len = this.length
  if (len % 2 !== 0) {
    throw new RangeError('Buffer size must be a multiple of 16-bits')
  }
  for (let i = 0; i < len; i += 2) {
    swap(this, i, i + 1)
  }
  return this
}

Buffer.prototype.swap32 = function swap32 () {
  const len = this.length
  if (len % 4 !== 0) {
    throw new RangeError('Buffer size must be a multiple of 32-bits')
  }
  for (let i = 0; i < len; i += 4) {
    swap(this, i, i + 3)
    swap(this, i + 1, i + 2)
  }
  return this
}

Buffer.prototype.swap64 = function swap64 () {
  const len = this.length
  if (len % 8 !== 0) {
    throw new RangeError('Buffer size must be a multiple of 64-bits')
  }
  for (let i = 0; i < len; i += 8) {
    swap(this, i, i + 7)
    swap(this, i + 1, i + 6)
    swap(this, i + 2, i + 5)
    swap(this, i + 3, i + 4)
  }
  return this
}

Buffer.prototype.toString = function toString () {
  const length = this.length
  if (length === 0) return ''
  if (arguments.length === 0) return utf8Slice(this, 0, length)
  return slowToString.apply(this, arguments)
}

Buffer.prototype.toLocaleString = Buffer.prototype.toString

Buffer.prototype.equals = function equals (b) {
  if (!Buffer.isBuffer(b)) throw new TypeError('Argument must be a Buffer')
  if (this === b) return true
  return Buffer.compare(this, b) === 0
}

Buffer.prototype.inspect = function inspect () {
  let str = ''
  const max = exports.INSPECT_MAX_BYTES
  str = this.toString('hex', 0, max).replace(/(.{2})/g, '$1 ').trim()
  if (this.length > max) str += ' ... '
  return '<Buffer ' + str + '>'
}
if (customInspectSymbol) {
  Buffer.prototype[customInspectSymbol] = Buffer.prototype.inspect
}

Buffer.prototype.compare = function compare (target, start, end, thisStart, thisEnd) {
  if (isInstance(target, Uint8Array)) {
    target = Buffer.from(target, target.offset, target.byteLength)
  }
  if (!Buffer.isBuffer(target)) {
    throw new TypeError(
      'The "target" argument must be one of type Buffer or Uint8Array. ' +
      'Received type ' + (typeof target)
    )
  }

  if (start === undefined) {
    start = 0
  }
  if (end === undefined) {
    end = target ? target.length : 0
  }
  if (thisStart === undefined) {
    thisStart = 0
  }
  if (thisEnd === undefined) {
    thisEnd = this.length
  }

  if (start < 0 || end > target.length || thisStart < 0 || thisEnd > this.length) {
    throw new RangeError('out of range index')
  }

  if (thisStart >= thisEnd && start >= end) {
    return 0
  }
  if (thisStart >= thisEnd) {
    return -1
  }
  if (start >= end) {
    return 1
  }

  start >>>= 0
  end >>>= 0
  thisStart >>>= 0
  thisEnd >>>= 0

  if (this === target) return 0

  let x = thisEnd - thisStart
  let y = end - start
  const len = Math.min(x, y)

  const thisCopy = this.slice(thisStart, thisEnd)
  const targetCopy = target.slice(start, end)

  for (let i = 0; i < len; ++i) {
    if (thisCopy[i] !== targetCopy[i]) {
      x = thisCopy[i]
      y = targetCopy[i]
      break
    }
  }

  if (x < y) return -1
  if (y < x) return 1
  return 0
}

// Finds either the first index of `val` in `buffer` at offset >= `byteOffset`,
// OR the last index of `val` in `buffer` at offset <= `byteOffset`.
//
// Arguments:
// - buffer - a Buffer to search
// - val - a string, Buffer, or number
// - byteOffset - an index into `buffer`; will be clamped to an int32
// - encoding - an optional encoding, relevant is val is a string
// - dir - true for indexOf, false for lastIndexOf
function bidirectionalIndexOf (buffer, val, byteOffset, encoding, dir) {
  // Empty buffer means no match
  if (buffer.length === 0) return -1

  // Normalize byteOffset
  if (typeof byteOffset === 'string') {
    encoding = byteOffset
    byteOffset = 0
  } else if (byteOffset > 0x7fffffff) {
    byteOffset = 0x7fffffff
  } else if (byteOffset < -0x80000000) {
    byteOffset = -0x80000000
  }
  byteOffset = +byteOffset // Coerce to Number.
  if (numberIsNaN(byteOffset)) {
    // byteOffset: it it's undefined, null, NaN, "foo", etc, search whole buffer
    byteOffset = dir ? 0 : (buffer.length - 1)
  }

  // Normalize byteOffset: negative offsets start from the end of the buffer
  if (byteOffset < 0) byteOffset = buffer.length + byteOffset
  if (byteOffset >= buffer.length) {
    if (dir) return -1
    else byteOffset = buffer.length - 1
  } else if (byteOffset < 0) {
    if (dir) byteOffset = 0
    else return -1
  }

  // Normalize val
  if (typeof val === 'string') {
    val = Buffer.from(val, encoding)
  }

  // Finally, search either indexOf (if dir is true) or lastIndexOf
  if (Buffer.isBuffer(val)) {
    // Special case: looking for empty string/buffer always fails
    if (val.length === 0) {
      return -1
    }
    return arrayIndexOf(buffer, val, byteOffset, encoding, dir)
  } else if (typeof val === 'number') {
    val = val & 0xFF // Search for a byte value [0-255]
    if (typeof Uint8Array.prototype.indexOf === 'function') {
      if (dir) {
        return Uint8Array.prototype.indexOf.call(buffer, val, byteOffset)
      } else {
        return Uint8Array.prototype.lastIndexOf.call(buffer, val, byteOffset)
      }
    }
    return arrayIndexOf(buffer, [val], byteOffset, encoding, dir)
  }

  throw new TypeError('val must be string, number or Buffer')
}

function arrayIndexOf (arr, val, byteOffset, encoding, dir) {
  let indexSize = 1
  let arrLength = arr.length
  let valLength = val.length

  if (encoding !== undefined) {
    encoding = String(encoding).toLowerCase()
    if (encoding === 'ucs2' || encoding === 'ucs-2' ||
        encoding === 'utf16le' || encoding === 'utf-16le') {
      if (arr.length < 2 || val.length < 2) {
        return -1
      }
      indexSize = 2
      arrLength /= 2
      valLength /= 2
      byteOffset /= 2
    }
  }

  function read (buf, i) {
    if (indexSize === 1) {
      return buf[i]
    } else {
      return buf.readUInt16BE(i * indexSize)
    }
  }

  let i
  if (dir) {
    let foundIndex = -1
    for (i = byteOffset; i < arrLength; i++) {
      if (read(arr, i) === read(val, foundIndex === -1 ? 0 : i - foundIndex)) {
        if (foundIndex === -1) foundIndex = i
        if (i - foundIndex + 1 === valLength) return foundIndex * indexSize
      } else {
        if (foundIndex !== -1) i -= i - foundIndex
        foundIndex = -1
      }
    }
  } else {
    if (byteOffset + valLength > arrLength) byteOffset = arrLength - valLength
    for (i = byteOffset; i >= 0; i--) {
      let found = true
      for (let j = 0; j < valLength; j++) {
        if (read(arr, i + j) !== read(val, j)) {
          found = false
          break
        }
      }
      if (found) return i
    }
  }

  return -1
}

Buffer.prototype.includes = function includes (val, byteOffset, encoding) {
  return this.indexOf(val, byteOffset, encoding) !== -1
}

Buffer.prototype.indexOf = function indexOf (val, byteOffset, encoding) {
  return bidirectionalIndexOf(this, val, byteOffset, encoding, true)
}

Buffer.prototype.lastIndexOf = function lastIndexOf (val, byteOffset, encoding) {
  return bidirectionalIndexOf(this, val, byteOffset, encoding, false)
}

function hexWrite (buf, string, offset, length) {
  offset = Number(offset) || 0
  const remaining = buf.length - offset
  if (!length) {
    length = remaining
  } else {
    length = Number(length)
    if (length > remaining) {
      length = remaining
    }
  }

  const strLen = string.length

  if (length > strLen / 2) {
    length = strLen / 2
  }
  let i
  for (i = 0; i < length; ++i) {
    const parsed = parseInt(string.substr(i * 2, 2), 16)
    if (numberIsNaN(parsed)) return i
    buf[offset + i] = parsed
  }
  return i
}

function utf8Write (buf, string, offset, length) {
  return blitBuffer(utf8ToBytes(string, buf.length - offset), buf, offset, length)
}

function asciiWrite (buf, string, offset, length) {
  return blitBuffer(asciiToBytes(string), buf, offset, length)
}

function base64Write (buf, string, offset, length) {
  return blitBuffer(base64ToBytes(string), buf, offset, length)
}

function ucs2Write (buf, string, offset, length) {
  return blitBuffer(utf16leToBytes(string, buf.length - offset), buf, offset, length)
}

Buffer.prototype.write = function write (string, offset, length, encoding) {
  // Buffer#write(string)
  if (offset === undefined) {
    encoding = 'utf8'
    length = this.length
    offset = 0
  // Buffer#write(string, encoding)
  } else if (length === undefined && typeof offset === 'string') {
    encoding = offset
    length = this.length
    offset = 0
  // Buffer#write(string, offset[, length][, encoding])
  } else if (isFinite(offset)) {
    offset = offset >>> 0
    if (isFinite(length)) {
      length = length >>> 0
      if (encoding === undefined) encoding = 'utf8'
    } else {
      encoding = length
      length = undefined
    }
  } else {
    throw new Error(
      'Buffer.write(string, encoding, offset[, length]) is no longer supported'
    )
  }

  const remaining = this.length - offset
  if (length === undefined || length > remaining) length = remaining

  if ((string.length > 0 && (length < 0 || offset < 0)) || offset > this.length) {
    throw new RangeError('Attempt to write outside buffer bounds')
  }

  if (!encoding) encoding = 'utf8'

  let loweredCase = false
  for (;;) {
    switch (encoding) {
      case 'hex':
        return hexWrite(this, string, offset, length)

      case 'utf8':
      case 'utf-8':
        return utf8Write(this, string, offset, length)

      case 'ascii':
      case 'latin1':
      case 'binary':
        return asciiWrite(this, string, offset, length)

      case 'base64':
        // Warning: maxLength not taken into account in base64Write
        return base64Write(this, string, offset, length)

      case 'ucs2':
      case 'ucs-2':
      case 'utf16le':
      case 'utf-16le':
        return ucs2Write(this, string, offset, length)

      default:
        if (loweredCase) throw new TypeError('Unknown encoding: ' + encoding)
        encoding = ('' + encoding).toLowerCase()
        loweredCase = true
    }
  }
}

Buffer.prototype.toJSON = function toJSON () {
  return {
    type: 'Buffer',
    data: Array.prototype.slice.call(this._arr || this, 0)
  }
}

function base64Slice (buf, start, end) {
  if (start === 0 && end === buf.length) {
    return base64.fromByteArray(buf)
  } else {
    return base64.fromByteArray(buf.slice(start, end))
  }
}

function utf8Slice (buf, start, end) {
  end = Math.min(buf.length, end)
  const res = []

  let i = start
  while (i < end) {
    const firstByte = buf[i]
    let codePoint = null
    let bytesPerSequence = (firstByte > 0xEF)
      ? 4
      : (firstByte > 0xDF)
          ? 3
          : (firstByte > 0xBF)
              ? 2
              : 1

    if (i + bytesPerSequence <= end) {
      let secondByte, thirdByte, fourthByte, tempCodePoint

      switch (bytesPerSequence) {
        case 1:
          if (firstByte < 0x80) {
            codePoint = firstByte
          }
          break
        case 2:
          secondByte = buf[i + 1]
          if ((secondByte & 0xC0) === 0x80) {
            tempCodePoint = (firstByte & 0x1F) << 0x6 | (secondByte & 0x3F)
            if (tempCodePoint > 0x7F) {
              codePoint = tempCodePoint
            }
          }
          break
        case 3:
          secondByte = buf[i + 1]
          thirdByte = buf[i + 2]
          if ((secondByte & 0xC0) === 0x80 && (thirdByte & 0xC0) === 0x80) {
            tempCodePoint = (firstByte & 0xF) << 0xC | (secondByte & 0x3F) << 0x6 | (thirdByte & 0x3F)
            if (tempCodePoint > 0x7FF && (tempCodePoint < 0xD800 || tempCodePoint > 0xDFFF)) {
              codePoint = tempCodePoint
            }
          }
          break
        case 4:
          secondByte = buf[i + 1]
          thirdByte = buf[i + 2]
          fourthByte = buf[i + 3]
          if ((secondByte & 0xC0) === 0x80 && (thirdByte & 0xC0) === 0x80 && (fourthByte & 0xC0) === 0x80) {
            tempCodePoint = (firstByte & 0xF) << 0x12 | (secondByte & 0x3F) << 0xC | (thirdByte & 0x3F) << 0x6 | (fourthByte & 0x3F)
            if (tempCodePoint > 0xFFFF && tempCodePoint < 0x110000) {
              codePoint = tempCodePoint
            }
          }
      }
    }

    if (codePoint === null) {
      // we did not generate a valid codePoint so insert a
      // replacement char (U+FFFD) and advance only 1 byte
      codePoint = 0xFFFD
      bytesPerSequence = 1
    } else if (codePoint > 0xFFFF) {
      // encode to utf16 (surrogate pair dance)
      codePoint -= 0x10000
      res.push(codePoint >>> 10 & 0x3FF | 0xD800)
      codePoint = 0xDC00 | codePoint & 0x3FF
    }

    res.push(codePoint)
    i += bytesPerSequence
  }

  return decodeCodePointsArray(res)
}

// Based on http://stackoverflow.com/a/22747272/680742, the browser with
// the lowest limit is Chrome, with 0x10000 args.
// We go 1 magnitude less, for safety
const MAX_ARGUMENTS_LENGTH = 0x1000

function decodeCodePointsArray (codePoints) {
  const len = codePoints.length
  if (len <= MAX_ARGUMENTS_LENGTH) {
    return String.fromCharCode.apply(String, codePoints) // avoid extra slice()
  }

  // Decode in chunks to avoid "call stack size exceeded".
  let res = ''
  let i = 0
  while (i < len) {
    res += String.fromCharCode.apply(
      String,
      codePoints.slice(i, i += MAX_ARGUMENTS_LENGTH)
    )
  }
  return res
}

function asciiSlice (buf, start, end) {
  let ret = ''
  end = Math.min(buf.length, end)

  for (let i = start; i < end; ++i) {
    ret += String.fromCharCode(buf[i] & 0x7F)
  }
  return ret
}

function latin1Slice (buf, start, end) {
  let ret = ''
  end = Math.min(buf.length, end)

  for (let i = start; i < end; ++i) {
    ret += String.fromCharCode(buf[i])
  }
  return ret
}

function hexSlice (buf, start, end) {
  const len = buf.length

  if (!start || start < 0) start = 0
  if (!end || end < 0 || end > len) end = len

  let out = ''
  for (let i = start; i < end; ++i) {
    out += hexSliceLookupTable[buf[i]]
  }
  return out
}

function utf16leSlice (buf, start, end) {
  const bytes = buf.slice(start, end)
  let res = ''
  // If bytes.length is odd, the last 8 bits must be ignored (same as node.js)
  for (let i = 0; i < bytes.length - 1; i += 2) {
    res += String.fromCharCode(bytes[i] + (bytes[i + 1] * 256))
  }
  return res
}

Buffer.prototype.slice = function slice (start, end) {
  const len = this.length
  start = ~~start
  end = end === undefined ? len : ~~end

  if (start < 0) {
    start += len
    if (start < 0) start = 0
  } else if (start > len) {
    start = len
  }

  if (end < 0) {
    end += len
    if (end < 0) end = 0
  } else if (end > len) {
    end = len
  }

  if (end < start) end = start

  const newBuf = this.subarray(start, end)
  // Return an augmented `Uint8Array` instance
  Object.setPrototypeOf(newBuf, Buffer.prototype)

  return newBuf
}

/*
 * Need to make sure that buffer isn't trying to write out of bounds.
 */
function checkOffset (offset, ext, length) {
  if ((offset % 1) !== 0 || offset < 0) throw new RangeError('offset is not uint')
  if (offset + ext > length) throw new RangeError('Trying to access beyond buffer length')
}

Buffer.prototype.readUintLE =
Buffer.prototype.readUIntLE = function readUIntLE (offset, byteLength, noAssert) {
  offset = offset >>> 0
  byteLength = byteLength >>> 0
  if (!noAssert) checkOffset(offset, byteLength, this.length)

  let val = this[offset]
  let mul = 1
  let i = 0
  while (++i < byteLength && (mul *= 0x100)) {
    val += this[offset + i] * mul
  }

  return val
}

Buffer.prototype.readUintBE =
Buffer.prototype.readUIntBE = function readUIntBE (offset, byteLength, noAssert) {
  offset = offset >>> 0
  byteLength = byteLength >>> 0
  if (!noAssert) {
    checkOffset(offset, byteLength, this.length)
  }

  let val = this[offset + --byteLength]
  let mul = 1
  while (byteLength > 0 && (mul *= 0x100)) {
    val += this[offset + --byteLength] * mul
  }

  return val
}

Buffer.prototype.readUint8 =
Buffer.prototype.readUInt8 = function readUInt8 (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 1, this.length)
  return this[offset]
}

Buffer.prototype.readUint16LE =
Buffer.prototype.readUInt16LE = function readUInt16LE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 2, this.length)
  return this[offset] | (this[offset + 1] << 8)
}

Buffer.prototype.readUint16BE =
Buffer.prototype.readUInt16BE = function readUInt16BE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 2, this.length)
  return (this[offset] << 8) | this[offset + 1]
}

Buffer.prototype.readUint32LE =
Buffer.prototype.readUInt32LE = function readUInt32LE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 4, this.length)

  return ((this[offset]) |
      (this[offset + 1] << 8) |
      (this[offset + 2] << 16)) +
      (this[offset + 3] * 0x1000000)
}

Buffer.prototype.readUint32BE =
Buffer.prototype.readUInt32BE = function readUInt32BE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 4, this.length)

  return (this[offset] * 0x1000000) +
    ((this[offset + 1] << 16) |
    (this[offset + 2] << 8) |
    this[offset + 3])
}

Buffer.prototype.readBigUInt64LE = defineBigIntMethod(function readBigUInt64LE (offset) {
  offset = offset >>> 0
  validateNumber(offset, 'offset')
  const first = this[offset]
  const last = this[offset + 7]
  if (first === undefined || last === undefined) {
    boundsError(offset, this.length - 8)
  }

  const lo = first +
    this[++offset] * 2 ** 8 +
    this[++offset] * 2 ** 16 +
    this[++offset] * 2 ** 24

  const hi = this[++offset] +
    this[++offset] * 2 ** 8 +
    this[++offset] * 2 ** 16 +
    last * 2 ** 24

  return BigInt(lo) + (BigInt(hi) << BigInt(32))
})

Buffer.prototype.readBigUInt64BE = defineBigIntMethod(function readBigUInt64BE (offset) {
  offset = offset >>> 0
  validateNumber(offset, 'offset')
  const first = this[offset]
  const last = this[offset + 7]
  if (first === undefined || last === undefined) {
    boundsError(offset, this.length - 8)
  }

  const hi = first * 2 ** 24 +
    this[++offset] * 2 ** 16 +
    this[++offset] * 2 ** 8 +
    this[++offset]

  const lo = this[++offset] * 2 ** 24 +
    this[++offset] * 2 ** 16 +
    this[++offset] * 2 ** 8 +
    last

  return (BigInt(hi) << BigInt(32)) + BigInt(lo)
})

Buffer.prototype.readIntLE = function readIntLE (offset, byteLength, noAssert) {
  offset = offset >>> 0
  byteLength = byteLength >>> 0
  if (!noAssert) checkOffset(offset, byteLength, this.length)

  let val = this[offset]
  let mul = 1
  let i = 0
  while (++i < byteLength && (mul *= 0x100)) {
    val += this[offset + i] * mul
  }
  mul *= 0x80

  if (val >= mul) val -= Math.pow(2, 8 * byteLength)

  return val
}

Buffer.prototype.readIntBE = function readIntBE (offset, byteLength, noAssert) {
  offset = offset >>> 0
  byteLength = byteLength >>> 0
  if (!noAssert) checkOffset(offset, byteLength, this.length)

  let i = byteLength
  let mul = 1
  let val = this[offset + --i]
  while (i > 0 && (mul *= 0x100)) {
    val += this[offset + --i] * mul
  }
  mul *= 0x80

  if (val >= mul) val -= Math.pow(2, 8 * byteLength)

  return val
}

Buffer.prototype.readInt8 = function readInt8 (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 1, this.length)
  if (!(this[offset] & 0x80)) return (this[offset])
  return ((0xff - this[offset] + 1) * -1)
}

Buffer.prototype.readInt16LE = function readInt16LE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 2, this.length)
  const val = this[offset] | (this[offset + 1] << 8)
  return (val & 0x8000) ? val | 0xFFFF0000 : val
}

Buffer.prototype.readInt16BE = function readInt16BE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 2, this.length)
  const val = this[offset + 1] | (this[offset] << 8)
  return (val & 0x8000) ? val | 0xFFFF0000 : val
}

Buffer.prototype.readInt32LE = function readInt32LE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 4, this.length)

  return (this[offset]) |
    (this[offset + 1] << 8) |
    (this[offset + 2] << 16) |
    (this[offset + 3] << 24)
}

Buffer.prototype.readInt32BE = function readInt32BE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 4, this.length)

  return (this[offset] << 24) |
    (this[offset + 1] << 16) |
    (this[offset + 2] << 8) |
    (this[offset + 3])
}

Buffer.prototype.readBigInt64LE = defineBigIntMethod(function readBigInt64LE (offset) {
  offset = offset >>> 0
  validateNumber(offset, 'offset')
  const first = this[offset]
  const last = this[offset + 7]
  if (first === undefined || last === undefined) {
    boundsError(offset, this.length - 8)
  }

  const val = this[offset + 4] +
    this[offset + 5] * 2 ** 8 +
    this[offset + 6] * 2 ** 16 +
    (last << 24) // Overflow

  return (BigInt(val) << BigInt(32)) +
    BigInt(first +
    this[++offset] * 2 ** 8 +
    this[++offset] * 2 ** 16 +
    this[++offset] * 2 ** 24)
})

Buffer.prototype.readBigInt64BE = defineBigIntMethod(function readBigInt64BE (offset) {
  offset = offset >>> 0
  validateNumber(offset, 'offset')
  const first = this[offset]
  const last = this[offset + 7]
  if (first === undefined || last === undefined) {
    boundsError(offset, this.length - 8)
  }

  const val = (first << 24) + // Overflow
    this[++offset] * 2 ** 16 +
    this[++offset] * 2 ** 8 +
    this[++offset]

  return (BigInt(val) << BigInt(32)) +
    BigInt(this[++offset] * 2 ** 24 +
    this[++offset] * 2 ** 16 +
    this[++offset] * 2 ** 8 +
    last)
})

Buffer.prototype.readFloatLE = function readFloatLE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 4, this.length)
  return ieee754.read(this, offset, true, 23, 4)
}

Buffer.prototype.readFloatBE = function readFloatBE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 4, this.length)
  return ieee754.read(this, offset, false, 23, 4)
}

Buffer.prototype.readDoubleLE = function readDoubleLE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 8, this.length)
  return ieee754.read(this, offset, true, 52, 8)
}

Buffer.prototype.readDoubleBE = function readDoubleBE (offset, noAssert) {
  offset = offset >>> 0
  if (!noAssert) checkOffset(offset, 8, this.length)
  return ieee754.read(this, offset, false, 52, 8)
}

function checkInt (buf, value, offset, ext, max, min) {
  if (!Buffer.isBuffer(buf)) throw new TypeError('"buffer" argument must be a Buffer instance')
  if (value > max || value < min) throw new RangeError('"value" argument is out of bounds')
  if (offset + ext > buf.length) throw new RangeError('Index out of range')
}

Buffer.prototype.writeUintLE =
Buffer.prototype.writeUIntLE = function writeUIntLE (value, offset, byteLength, noAssert) {
  value = +value
  offset = offset >>> 0
  byteLength = byteLength >>> 0
  if (!noAssert) {
    const maxBytes = Math.pow(2, 8 * byteLength) - 1
    checkInt(this, value, offset, byteLength, maxBytes, 0)
  }

  let mul = 1
  let i = 0
  this[offset] = value & 0xFF
  while (++i < byteLength && (mul *= 0x100)) {
    this[offset + i] = (value / mul) & 0xFF
  }

  return offset + byteLength
}

Buffer.prototype.writeUintBE =
Buffer.prototype.writeUIntBE = function writeUIntBE (value, offset, byteLength, noAssert) {
  value = +value
  offset = offset >>> 0
  byteLength = byteLength >>> 0
  if (!noAssert) {
    const maxBytes = Math.pow(2, 8 * byteLength) - 1
    checkInt(this, value, offset, byteLength, maxBytes, 0)
  }

  let i = byteLength - 1
  let mul = 1
  this[offset + i] = value & 0xFF
  while (--i >= 0 && (mul *= 0x100)) {
    this[offset + i] = (value / mul) & 0xFF
  }

  return offset + byteLength
}

Buffer.prototype.writeUint8 =
Buffer.prototype.writeUInt8 = function writeUInt8 (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 1, 0xff, 0)
  this[offset] = (value & 0xff)
  return offset + 1
}

Buffer.prototype.writeUint16LE =
Buffer.prototype.writeUInt16LE = function writeUInt16LE (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 2, 0xffff, 0)
  this[offset] = (value & 0xff)
  this[offset + 1] = (value >>> 8)
  return offset + 2
}

Buffer.prototype.writeUint16BE =
Buffer.prototype.writeUInt16BE = function writeUInt16BE (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 2, 0xffff, 0)
  this[offset] = (value >>> 8)
  this[offset + 1] = (value & 0xff)
  return offset + 2
}

Buffer.prototype.writeUint32LE =
Buffer.prototype.writeUInt32LE = function writeUInt32LE (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 4, 0xffffffff, 0)
  this[offset + 3] = (value >>> 24)
  this[offset + 2] = (value >>> 16)
  this[offset + 1] = (value >>> 8)
  this[offset] = (value & 0xff)
  return offset + 4
}

Buffer.prototype.writeUint32BE =
Buffer.prototype.writeUInt32BE = function writeUInt32BE (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 4, 0xffffffff, 0)
  this[offset] = (value >>> 24)
  this[offset + 1] = (value >>> 16)
  this[offset + 2] = (value >>> 8)
  this[offset + 3] = (value & 0xff)
  return offset + 4
}

function wrtBigUInt64LE (buf, value, offset, min, max) {
  checkIntBI(value, min, max, buf, offset, 7)

  let lo = Number(value & BigInt(0xffffffff))
  buf[offset++] = lo
  lo = lo >> 8
  buf[offset++] = lo
  lo = lo >> 8
  buf[offset++] = lo
  lo = lo >> 8
  buf[offset++] = lo
  let hi = Number(value >> BigInt(32) & BigInt(0xffffffff))
  buf[offset++] = hi
  hi = hi >> 8
  buf[offset++] = hi
  hi = hi >> 8
  buf[offset++] = hi
  hi = hi >> 8
  buf[offset++] = hi
  return offset
}

function wrtBigUInt64BE (buf, value, offset, min, max) {
  checkIntBI(value, min, max, buf, offset, 7)

  let lo = Number(value & BigInt(0xffffffff))
  buf[offset + 7] = lo
  lo = lo >> 8
  buf[offset + 6] = lo
  lo = lo >> 8
  buf[offset + 5] = lo
  lo = lo >> 8
  buf[offset + 4] = lo
  let hi = Number(value >> BigInt(32) & BigInt(0xffffffff))
  buf[offset + 3] = hi
  hi = hi >> 8
  buf[offset + 2] = hi
  hi = hi >> 8
  buf[offset + 1] = hi
  hi = hi >> 8
  buf[offset] = hi
  return offset + 8
}

Buffer.prototype.writeBigUInt64LE = defineBigIntMethod(function writeBigUInt64LE (value, offset = 0) {
  return wrtBigUInt64LE(this, value, offset, BigInt(0), BigInt('0xffffffffffffffff'))
})

Buffer.prototype.writeBigUInt64BE = defineBigIntMethod(function writeBigUInt64BE (value, offset = 0) {
  return wrtBigUInt64BE(this, value, offset, BigInt(0), BigInt('0xffffffffffffffff'))
})

Buffer.prototype.writeIntLE = function writeIntLE (value, offset, byteLength, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) {
    const limit = Math.pow(2, (8 * byteLength) - 1)

    checkInt(this, value, offset, byteLength, limit - 1, -limit)
  }

  let i = 0
  let mul = 1
  let sub = 0
  this[offset] = value & 0xFF
  while (++i < byteLength && (mul *= 0x100)) {
    if (value < 0 && sub === 0 && this[offset + i - 1] !== 0) {
      sub = 1
    }
    this[offset + i] = ((value / mul) >> 0) - sub & 0xFF
  }

  return offset + byteLength
}

Buffer.prototype.writeIntBE = function writeIntBE (value, offset, byteLength, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) {
    const limit = Math.pow(2, (8 * byteLength) - 1)

    checkInt(this, value, offset, byteLength, limit - 1, -limit)
  }

  let i = byteLength - 1
  let mul = 1
  let sub = 0
  this[offset + i] = value & 0xFF
  while (--i >= 0 && (mul *= 0x100)) {
    if (value < 0 && sub === 0 && this[offset + i + 1] !== 0) {
      sub = 1
    }
    this[offset + i] = ((value / mul) >> 0) - sub & 0xFF
  }

  return offset + byteLength
}

Buffer.prototype.writeInt8 = function writeInt8 (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 1, 0x7f, -0x80)
  if (value < 0) value = 0xff + value + 1
  this[offset] = (value & 0xff)
  return offset + 1
}

Buffer.prototype.writeInt16LE = function writeInt16LE (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 2, 0x7fff, -0x8000)
  this[offset] = (value & 0xff)
  this[offset + 1] = (value >>> 8)
  return offset + 2
}

Buffer.prototype.writeInt16BE = function writeInt16BE (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 2, 0x7fff, -0x8000)
  this[offset] = (value >>> 8)
  this[offset + 1] = (value & 0xff)
  return offset + 2
}

Buffer.prototype.writeInt32LE = function writeInt32LE (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 4, 0x7fffffff, -0x80000000)
  this[offset] = (value & 0xff)
  this[offset + 1] = (value >>> 8)
  this[offset + 2] = (value >>> 16)
  this[offset + 3] = (value >>> 24)
  return offset + 4
}

Buffer.prototype.writeInt32BE = function writeInt32BE (value, offset, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) checkInt(this, value, offset, 4, 0x7fffffff, -0x80000000)
  if (value < 0) value = 0xffffffff + value + 1
  this[offset] = (value >>> 24)
  this[offset + 1] = (value >>> 16)
  this[offset + 2] = (value >>> 8)
  this[offset + 3] = (value & 0xff)
  return offset + 4
}

Buffer.prototype.writeBigInt64LE = defineBigIntMethod(function writeBigInt64LE (value, offset = 0) {
  return wrtBigUInt64LE(this, value, offset, -BigInt('0x8000000000000000'), BigInt('0x7fffffffffffffff'))
})

Buffer.prototype.writeBigInt64BE = defineBigIntMethod(function writeBigInt64BE (value, offset = 0) {
  return wrtBigUInt64BE(this, value, offset, -BigInt('0x8000000000000000'), BigInt('0x7fffffffffffffff'))
})

function checkIEEE754 (buf, value, offset, ext, max, min) {
  if (offset + ext > buf.length) throw new RangeError('Index out of range')
  if (offset < 0) throw new RangeError('Index out of range')
}

function writeFloat (buf, value, offset, littleEndian, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) {
    checkIEEE754(buf, value, offset, 4, 3.4028234663852886e+38, -3.4028234663852886e+38)
  }
  ieee754.write(buf, value, offset, littleEndian, 23, 4)
  return offset + 4
}

Buffer.prototype.writeFloatLE = function writeFloatLE (value, offset, noAssert) {
  return writeFloat(this, value, offset, true, noAssert)
}

Buffer.prototype.writeFloatBE = function writeFloatBE (value, offset, noAssert) {
  return writeFloat(this, value, offset, false, noAssert)
}

function writeDouble (buf, value, offset, littleEndian, noAssert) {
  value = +value
  offset = offset >>> 0
  if (!noAssert) {
    checkIEEE754(buf, value, offset, 8, 1.7976931348623157E+308, -1.7976931348623157E+308)
  }
  ieee754.write(buf, value, offset, littleEndian, 52, 8)
  return offset + 8
}

Buffer.prototype.writeDoubleLE = function writeDoubleLE (value, offset, noAssert) {
  return writeDouble(this, value, offset, true, noAssert)
}

Buffer.prototype.writeDoubleBE = function writeDoubleBE (value, offset, noAssert) {
  return writeDouble(this, value, offset, false, noAssert)
}

// copy(targetBuffer, targetStart=0, sourceStart=0, sourceEnd=buffer.length)
Buffer.prototype.copy = function copy (target, targetStart, start, end) {
  if (!Buffer.isBuffer(target)) throw new TypeError('argument should be a Buffer')
  if (!start) start = 0
  if (!end && end !== 0) end = this.length
  if (targetStart >= target.length) targetStart = target.length
  if (!targetStart) targetStart = 0
  if (end > 0 && end < start) end = start

  // Copy 0 bytes; we're done
  if (end === start) return 0
  if (target.length === 0 || this.length === 0) return 0

  // Fatal error conditions
  if (targetStart < 0) {
    throw new RangeError('targetStart out of bounds')
  }
  if (start < 0 || start >= this.length) throw new RangeError('Index out of range')
  if (end < 0) throw new RangeError('sourceEnd out of bounds')

  // Are we oob?
  if (end > this.length) end = this.length
  if (target.length - targetStart < end - start) {
    end = target.length - targetStart + start
  }

  const len = end - start

  if (this === target && typeof Uint8Array.prototype.copyWithin === 'function') {
    // Use built-in when available, missing from IE11
    this.copyWithin(targetStart, start, end)
  } else {
    Uint8Array.prototype.set.call(
      target,
      this.subarray(start, end),
      targetStart
    )
  }

  return len
}

// Usage:
//    buffer.fill(number[, offset[, end]])
//    buffer.fill(buffer[, offset[, end]])
//    buffer.fill(string[, offset[, end]][, encoding])
Buffer.prototype.fill = function fill (val, start, end, encoding) {
  // Handle string cases:
  if (typeof val === 'string') {
    if (typeof start === 'string') {
      encoding = start
      start = 0
      end = this.length
    } else if (typeof end === 'string') {
      encoding = end
      end = this.length
    }
    if (encoding !== undefined && typeof encoding !== 'string') {
      throw new TypeError('encoding must be a string')
    }
    if (typeof encoding === 'string' && !Buffer.isEncoding(encoding)) {
      throw new TypeError('Unknown encoding: ' + encoding)
    }
    if (val.length === 1) {
      const code = val.charCodeAt(0)
      if ((encoding === 'utf8' && code < 128) ||
          encoding === 'latin1') {
        // Fast path: If `val` fits into a single byte, use that numeric value.
        val = code
      }
    }
  } else if (typeof val === 'number') {
    val = val & 255
  } else if (typeof val === 'boolean') {
    val = Number(val)
  }

  // Invalid ranges are not set to a default, so can range check early.
  if (start < 0 || this.length < start || this.length < end) {
    throw new RangeError('Out of range index')
  }

  if (end <= start) {
    return this
  }

  start = start >>> 0
  end = end === undefined ? this.length : end >>> 0

  if (!val) val = 0

  let i
  if (typeof val === 'number') {
    for (i = start; i < end; ++i) {
      this[i] = val
    }
  } else {
    const bytes = Buffer.isBuffer(val)
      ? val
      : Buffer.from(val, encoding)
    const len = bytes.length
    if (len === 0) {
      throw new TypeError('The value "' + val +
        '" is invalid for argument "value"')
    }
    for (i = 0; i < end - start; ++i) {
      this[i + start] = bytes[i % len]
    }
  }

  return this
}

// CUSTOM ERRORS
// =============

// Simplified versions from Node, changed for Buffer-only usage
const errors = {}
function E (sym, getMessage, Base) {
  errors[sym] = class NodeError extends Base {
    constructor () {
      super()

      Object.defineProperty(this, 'message', {
        value: getMessage.apply(this, arguments),
        writable: true,
        configurable: true
      })

      // Add the error code to the name to include it in the stack trace.
      this.name = `${this.name} [${sym}]`
      // Access the stack to generate the error message including the error code
      // from the name.
      this.stack // eslint-disable-line no-unused-expressions
      // Reset the name to the actual name.
      delete this.name
    }

    get code () {
      return sym
    }

    set code (value) {
      Object.defineProperty(this, 'code', {
        configurable: true,
        enumerable: true,
        value,
        writable: true
      })
    }

    toString () {
      return `${this.name} [${sym}]: ${this.message}`
    }
  }
}

E('ERR_BUFFER_OUT_OF_BOUNDS',
  function (name) {
    if (name) {
      return `${name} is outside of buffer bounds`
    }

    return 'Attempt to access memory outside buffer bounds'
  }, RangeError)
E('ERR_INVALID_ARG_TYPE',
  function (name, actual) {
    return `The "${name}" argument must be of type number. Received type ${typeof actual}`
  }, TypeError)
E('ERR_OUT_OF_RANGE',
  function (str, range, input) {
    let msg = `The value of "${str}" is out of range.`
    let received = input
    if (Number.isInteger(input) && Math.abs(input) > 2 ** 32) {
      received = addNumericalSeparator(String(input))
    } else if (typeof input === 'bigint') {
      received = String(input)
      if (input > BigInt(2) ** BigInt(32) || input < -(BigInt(2) ** BigInt(32))) {
        received = addNumericalSeparator(received)
      }
      received += 'n'
    }
    msg += ` It must be ${range}. Received ${received}`
    return msg
  }, RangeError)

function addNumericalSeparator (val) {
  let res = ''
  let i = val.length
  const start = val[0] === '-' ? 1 : 0
  for (; i >= start + 4; i -= 3) {
    res = `_${val.slice(i - 3, i)}${res}`
  }
  return `${val.slice(0, i)}${res}`
}

// CHECK FUNCTIONS
// ===============

function checkBounds (buf, offset, byteLength) {
  validateNumber(offset, 'offset')
  if (buf[offset] === undefined || buf[offset + byteLength] === undefined) {
    boundsError(offset, buf.length - (byteLength + 1))
  }
}

function checkIntBI (value, min, max, buf, offset, byteLength) {
  if (value > max || value < min) {
    const n = typeof min === 'bigint' ? 'n' : ''
    let range
    if (byteLength > 3) {
      if (min === 0 || min === BigInt(0)) {
        range = `>= 0${n} and < 2${n} ** ${(byteLength + 1) * 8}${n}`
      } else {
        range = `>= -(2${n} ** ${(byteLength + 1) * 8 - 1}${n}) and < 2 ** ` +
                `${(byteLength + 1) * 8 - 1}${n}`
      }
    } else {
      range = `>= ${min}${n} and <= ${max}${n}`
    }
    throw new errors.ERR_OUT_OF_RANGE('value', range, value)
  }
  checkBounds(buf, offset, byteLength)
}

function validateNumber (value, name) {
  if (typeof value !== 'number') {
    throw new errors.ERR_INVALID_ARG_TYPE(name, 'number', value)
  }
}

function boundsError (value, length, type) {
  if (Math.floor(value) !== value) {
    validateNumber(value, type)
    throw new errors.ERR_OUT_OF_RANGE(type || 'offset', 'an integer', value)
  }

  if (length < 0) {
    throw new errors.ERR_BUFFER_OUT_OF_BOUNDS()
  }

  throw new errors.ERR_OUT_OF_RANGE(type || 'offset',
                                    `>= ${type ? 1 : 0} and <= ${length}`,
                                    value)
}

// HELPER FUNCTIONS
// ================

const INVALID_BASE64_RE = /[^+/0-9A-Za-z-_]/g

function base64clean (str) {
  // Node takes equal signs as end of the Base64 encoding
  str = str.split('=')[0]
  // Node strips out invalid characters like \n and \t from the string, base64-js does not
  str = str.trim().replace(INVALID_BASE64_RE, '')
  // Node converts strings with length < 2 to ''
  if (str.length < 2) return ''
  // Node allows for non-padded base64 strings (missing trailing ===), base64-js does not
  while (str.length % 4 !== 0) {
    str = str + '='
  }
  return str
}

function utf8ToBytes (string, units) {
  units = units || Infinity
  let codePoint
  const length = string.length
  let leadSurrogate = null
  const bytes = []

  for (let i = 0; i < length; ++i) {
    codePoint = string.charCodeAt(i)

    // is surrogate component
    if (codePoint > 0xD7FF && codePoint < 0xE000) {
      // last char was a lead
      if (!leadSurrogate) {
        // no lead yet
        if (codePoint > 0xDBFF) {
          // unexpected trail
          if ((units -= 3) > -1) bytes.push(0xEF, 0xBF, 0xBD)
          continue
        } else if (i + 1 === length) {
          // unpaired lead
          if ((units -= 3) > -1) bytes.push(0xEF, 0xBF, 0xBD)
          continue
        }

        // valid lead
        leadSurrogate = codePoint

        continue
      }

      // 2 leads in a row
      if (codePoint < 0xDC00) {
        if ((units -= 3) > -1) bytes.push(0xEF, 0xBF, 0xBD)
        leadSurrogate = codePoint
        continue
      }

      // valid surrogate pair
      codePoint = (leadSurrogate - 0xD800 << 10 | codePoint - 0xDC00) + 0x10000
    } else if (leadSurrogate) {
      // valid bmp char, but last char was a lead
      if ((units -= 3) > -1) bytes.push(0xEF, 0xBF, 0xBD)
    }

    leadSurrogate = null

    // encode utf8
    if (codePoint < 0x80) {
      if ((units -= 1) < 0) break
      bytes.push(codePoint)
    } else if (codePoint < 0x800) {
      if ((units -= 2) < 0) break
      bytes.push(
        codePoint >> 0x6 | 0xC0,
        codePoint & 0x3F | 0x80
      )
    } else if (codePoint < 0x10000) {
      if ((units -= 3) < 0) break
      bytes.push(
        codePoint >> 0xC | 0xE0,
        codePoint >> 0x6 & 0x3F | 0x80,
        codePoint & 0x3F | 0x80
      )
    } else if (codePoint < 0x110000) {
      if ((units -= 4) < 0) break
      bytes.push(
        codePoint >> 0x12 | 0xF0,
        codePoint >> 0xC & 0x3F | 0x80,
        codePoint >> 0x6 & 0x3F | 0x80,
        codePoint & 0x3F | 0x80
      )
    } else {
      throw new Error('Invalid code point')
    }
  }

  return bytes
}

function asciiToBytes (str) {
  const byteArray = []
  for (let i = 0; i < str.length; ++i) {
    // Node's code seems to be doing this and not & 0x7F..
    byteArray.push(str.charCodeAt(i) & 0xFF)
  }
  return byteArray
}

function utf16leToBytes (str, units) {
  let c, hi, lo
  const byteArray = []
  for (let i = 0; i < str.length; ++i) {
    if ((units -= 2) < 0) break

    c = str.charCodeAt(i)
    hi = c >> 8
    lo = c % 256
    byteArray.push(lo)
    byteArray.push(hi)
  }

  return byteArray
}

function base64ToBytes (str) {
  return base64.toByteArray(base64clean(str))
}

function blitBuffer (src, dst, offset, length) {
  let i
  for (i = 0; i < length; ++i) {
    if ((i + offset >= dst.length) || (i >= src.length)) break
    dst[i + offset] = src[i]
  }
  return i
}

// ArrayBuffer or Uint8Array objects from other contexts (i.e. iframes) do not pass
// the `instanceof` check but they should be treated as of that type.
// See: https://github.com/feross/buffer/issues/166
function isInstance (obj, type) {
  return obj instanceof type ||
    (obj != null && obj.constructor != null && obj.constructor.name != null &&
      obj.constructor.name === type.name)
}
function numberIsNaN (obj) {
  // For IE11 support
  return obj !== obj // eslint-disable-line no-self-compare
}

// Create lookup table for `toString('hex')`
// See: https://github.com/feross/buffer/issues/219
const hexSliceLookupTable = (function () {
  const alphabet = '0123456789abcdef'
  const table = new Array(256)
  for (let i = 0; i < 16; ++i) {
    const i16 = i * 16
    for (let j = 0; j < 16; ++j) {
      table[i16 + j] = alphabet[i] + alphabet[j]
    }
  }
  return table
})()

// Return not function with Error if BigInt not supported
function defineBigIntMethod (fn) {
  return typeof BigInt === 'undefined' ? BufferBigIntNotDefined : fn
}

function BufferBigIntNotDefined () {
  throw new Error('BigInt not supported')
}


/***/ },

/***/ 251
(__unused_webpack_module, exports) {

/*! ieee754. BSD-3-Clause License. Feross Aboukhadijeh <https://feross.org/opensource> */
exports.read = function (buffer, offset, isLE, mLen, nBytes) {
  var e, m
  var eLen = (nBytes * 8) - mLen - 1
  var eMax = (1 << eLen) - 1
  var eBias = eMax >> 1
  var nBits = -7
  var i = isLE ? (nBytes - 1) : 0
  var d = isLE ? -1 : 1
  var s = buffer[offset + i]

  i += d

  e = s & ((1 << (-nBits)) - 1)
  s >>= (-nBits)
  nBits += eLen
  for (; nBits > 0; e = (e * 256) + buffer[offset + i], i += d, nBits -= 8) {}

  m = e & ((1 << (-nBits)) - 1)
  e >>= (-nBits)
  nBits += mLen
  for (; nBits > 0; m = (m * 256) + buffer[offset + i], i += d, nBits -= 8) {}

  if (e === 0) {
    e = 1 - eBias
  } else if (e === eMax) {
    return m ? NaN : ((s ? -1 : 1) * Infinity)
  } else {
    m = m + Math.pow(2, mLen)
    e = e - eBias
  }
  return (s ? -1 : 1) * m * Math.pow(2, e - mLen)
}

exports.write = function (buffer, value, offset, isLE, mLen, nBytes) {
  var e, m, c
  var eLen = (nBytes * 8) - mLen - 1
  var eMax = (1 << eLen) - 1
  var eBias = eMax >> 1
  var rt = (mLen === 23 ? Math.pow(2, -24) - Math.pow(2, -77) : 0)
  var i = isLE ? 0 : (nBytes - 1)
  var d = isLE ? 1 : -1
  var s = value < 0 || (value === 0 && 1 / value < 0) ? 1 : 0

  value = Math.abs(value)

  if (isNaN(value) || value === Infinity) {
    m = isNaN(value) ? 1 : 0
    e = eMax
  } else {
    e = Math.floor(Math.log(value) / Math.LN2)
    if (value * (c = Math.pow(2, -e)) < 1) {
      e--
      c *= 2
    }
    if (e + eBias >= 1) {
      value += rt / c
    } else {
      value += rt * Math.pow(2, 1 - eBias)
    }
    if (value * c >= 2) {
      e++
      c /= 2
    }

    if (e + eBias >= eMax) {
      m = 0
      e = eMax
    } else if (e + eBias >= 1) {
      m = ((value * c) - 1) * Math.pow(2, mLen)
      e = e + eBias
    } else {
      m = value * Math.pow(2, eBias - 1) * Math.pow(2, mLen)
      e = 0
    }
  }

  for (; mLen >= 8; buffer[offset + i] = m & 0xff, i += d, m /= 256, mLen -= 8) {}

  e = (e << mLen) | m
  eLen += mLen
  for (; eLen > 0; buffer[offset + i] = e & 0xff, i += d, e /= 256, eLen -= 8) {}

  buffer[offset + i - d] |= s * 128
}


/***/ },

/***/ 869
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   createCipher: () => (/* binding */ createCipher),
/* harmony export */   rotl: () => (/* binding */ rotl)
/* harmony export */ });
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(34);
/**
 * Basic utils for ARX (add-rotate-xor) salsa and chacha ciphers.

RFC8439 requires multi-step cipher stream, where
authKey starts with counter: 0, actual msg with counter: 1.

For this, we need a way to re-use nonce / counter:

    const counter = new Uint8Array(4);
    chacha(..., counter, ...); // counter is now 1
    chacha(..., counter, ...); // counter is now 2

This is complicated:

- 32-bit counters are enough, no need for 64-bit: max ArrayBuffer size in JS is 4GB
- Original papers don't allow mutating counters
- Counter overflow is undefined [^1]
- Idea A: allow providing (nonce | counter) instead of just nonce, re-use it
- Caveat: Cannot be re-used through all cases:
- * chacha has (counter | nonce)
- * xchacha has (nonce16 | counter | nonce16)
- Idea B: separate nonce / counter and provide separate API for counter re-use
- Caveat: there are different counter sizes depending on an algorithm.
- salsa & chacha also differ in structures of key & sigma:
  salsa20:      s[0] | k(4) | s[1] | nonce(2) | ctr(2) | s[2] | k(4) | s[3]
  chacha:       s(4) | k(8) | ctr(1) | nonce(3)
  chacha20orig: s(4) | k(8) | ctr(2) | nonce(2)
- Idea C: helper method such as `setSalsaState(key, nonce, sigma, data)`
- Caveat: we can't re-use counter array

xchacha [^2] uses the subkey and remaining 8 byte nonce with ChaCha20 as normal
(prefixed by 4 NUL bytes, since [RFC8439] specifies a 12-byte nonce).

[^1]: https://mailarchive.ietf.org/arch/msg/cfrg/gsOnTJzcbgG6OqD8Sc0GO5aR_tU/
[^2]: https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-xchacha#appendix-A.2

 * @module
 */
// prettier-ignore

// We can't make top-level var depend on utils.utf8ToBytes
// because it's not present in all envs. Creating a similar fn here
const _utf8ToBytes = (str) => Uint8Array.from(str.split('').map((c) => c.charCodeAt(0)));
const sigma16 = _utf8ToBytes('expand 16-byte k');
const sigma32 = _utf8ToBytes('expand 32-byte k');
const sigma16_32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(sigma16);
const sigma32_32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(sigma32);
function rotl(a, b) {
    return (a << b) | (a >>> (32 - b));
}
// Is byte array aligned to 4 byte offset (u32)?
function isAligned32(b) {
    return b.byteOffset % 4 === 0;
}
// Salsa and Chacha block length is always 512-bit
const BLOCK_LEN = 64;
const BLOCK_LEN32 = 16;
// new Uint32Array([2**32])   // => Uint32Array(1) [ 0 ]
// new Uint32Array([2**32-1]) // => Uint32Array(1) [ 4294967295 ]
const MAX_COUNTER = 2 ** 32 - 1;
const U32_EMPTY = new Uint32Array();
function runCipher(core, sigma, key, nonce, data, output, counter, rounds) {
    const len = data.length;
    const block = new Uint8Array(BLOCK_LEN);
    const b32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(block);
    // Make sure that buffers aligned to 4 bytes
    const isAligned = isAligned32(data) && isAligned32(output);
    const d32 = isAligned ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(data) : U32_EMPTY;
    const o32 = isAligned ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(output) : U32_EMPTY;
    for (let pos = 0; pos < len; counter++) {
        core(sigma, key, nonce, b32, counter, rounds);
        if (counter >= MAX_COUNTER)
            throw new Error('arx: counter overflow');
        const take = Math.min(BLOCK_LEN, len - pos);
        // aligned to 4 bytes
        if (isAligned && take === BLOCK_LEN) {
            const pos32 = pos / 4;
            if (pos % 4 !== 0)
                throw new Error('arx: invalid block position');
            for (let j = 0, posj; j < BLOCK_LEN32; j++) {
                posj = pos32 + j;
                o32[posj] = d32[posj] ^ b32[j];
            }
            pos += BLOCK_LEN;
            continue;
        }
        for (let j = 0, posj; j < take; j++) {
            posj = pos + j;
            output[posj] = data[posj] ^ block[j];
        }
        pos += take;
    }
}
/** Creates ARX-like (ChaCha, Salsa) cipher stream from core function. */
function createCipher(core, opts) {
    const { allowShortKeys, extendNonceFn, counterLength, counterRight, rounds } = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.checkOpts)({ allowShortKeys: false, counterLength: 8, counterRight: false, rounds: 20 }, opts);
    if (typeof core !== 'function')
        throw new Error('core must be a function');
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.anumber)(counterLength);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.anumber)(rounds);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abool)(counterRight);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abool)(allowShortKeys);
    return (key, nonce, data, output, counter = 0) => {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(key);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(nonce);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(data);
        const len = data.length;
        if (output === undefined)
            output = new Uint8Array(len);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(output);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.anumber)(counter);
        if (counter < 0 || counter >= MAX_COUNTER)
            throw new Error('arx: counter overflow');
        if (output.length < len)
            throw new Error(`arx: output (${output.length}) is shorter than data (${len})`);
        const toClean = [];
        // Key & sigma
        // key=16 -> sigma16, k=key|key
        // key=32 -> sigma32, k=key
        let l = key.length;
        let k;
        let sigma;
        if (l === 32) {
            toClean.push((k = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.copyBytes)(key)));
            sigma = sigma32_32;
        }
        else if (l === 16 && allowShortKeys) {
            k = new Uint8Array(32);
            k.set(key);
            k.set(key, 16);
            sigma = sigma16_32;
            toClean.push(k);
        }
        else {
            throw new Error(`arx: invalid 32-byte key, got length=${l}`);
        }
        // Nonce
        // salsa20:      8   (8-byte counter)
        // chacha20orig: 8   (8-byte counter)
        // chacha20:     12  (4-byte counter)
        // xsalsa20:     24  (16 -> hsalsa,  8 -> old nonce)
        // xchacha20:    24  (16 -> hchacha, 8 -> old nonce)
        // Align nonce to 4 bytes
        if (!isAligned32(nonce))
            toClean.push((nonce = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.copyBytes)(nonce)));
        const k32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(k);
        // hsalsa & hchacha: handle extended nonce
        if (extendNonceFn) {
            if (nonce.length !== 24)
                throw new Error(`arx: extended nonce must be 24 bytes`);
            extendNonceFn(sigma, k32, (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(nonce.subarray(0, 16)), k32);
            nonce = nonce.subarray(16);
        }
        // Handle nonce counter
        const nonceNcLen = 16 - counterLength;
        if (nonceNcLen !== nonce.length)
            throw new Error(`arx: nonce must be ${nonceNcLen} or 16 bytes`);
        // Pad counter when nonce is 64 bit
        if (nonceNcLen !== 12) {
            const nc = new Uint8Array(12);
            nc.set(nonce, counterRight ? 0 : 12 - nonce.length);
            nonce = nc;
            toClean.push(nonce);
        }
        const n32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(nonce);
        runCipher(core, sigma, k32, n32, data, output, counter, rounds);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(...toClean);
        return output;
    };
}
//# sourceMappingURL=_arx.js.map

/***/ },

/***/ 935
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   poly1305: () => (/* binding */ poly1305)
/* harmony export */ });
/* unused harmony export wrapConstructorWithKey */
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(34);
/**
 * Poly1305 ([PDF](https://cr.yp.to/mac/poly1305-20050329.pdf),
 * [wiki](https://en.wikipedia.org/wiki/Poly1305))
 * is a fast and parallel secret-key message-authentication code suitable for
 * a wide variety of applications. It was standardized in
 * [RFC 8439](https://datatracker.ietf.org/doc/html/rfc8439) and is now used in TLS 1.3.
 *
 * Polynomial MACs are not perfect for every situation:
 * they lack Random Key Robustness: the MAC can be forged, and can't be used in PAKE schemes.
 * See [invisible salamanders attack](https://keymaterial.net/2020/09/07/invisible-salamanders-in-aes-gcm-siv/).
 * To combat invisible salamanders, `hash(key)` can be included in ciphertext,
 * however, this would violate ciphertext indistinguishability:
 * an attacker would know which key was used - so `HKDF(key, i)`
 * could be used instead.
 *
 * Check out [original website](https://cr.yp.to/mac.html).
 * @module
 */

// Based on Public Domain poly1305-donna https://github.com/floodyberry/poly1305-donna
const u8to16 = (a, i) => (a[i++] & 0xff) | ((a[i++] & 0xff) << 8);
class Poly1305 {
    constructor(key) {
        this.blockLen = 16;
        this.outputLen = 16;
        this.buffer = new Uint8Array(16);
        this.r = new Uint16Array(10);
        this.h = new Uint16Array(10);
        this.pad = new Uint16Array(8);
        this.pos = 0;
        this.finished = false;
        key = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(key);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(key, 32);
        const t0 = u8to16(key, 0);
        const t1 = u8to16(key, 2);
        const t2 = u8to16(key, 4);
        const t3 = u8to16(key, 6);
        const t4 = u8to16(key, 8);
        const t5 = u8to16(key, 10);
        const t6 = u8to16(key, 12);
        const t7 = u8to16(key, 14);
        // https://github.com/floodyberry/poly1305-donna/blob/e6ad6e091d30d7f4ec2d4f978be1fcfcbce72781/poly1305-donna-16.h#L47
        this.r[0] = t0 & 0x1fff;
        this.r[1] = ((t0 >>> 13) | (t1 << 3)) & 0x1fff;
        this.r[2] = ((t1 >>> 10) | (t2 << 6)) & 0x1f03;
        this.r[3] = ((t2 >>> 7) | (t3 << 9)) & 0x1fff;
        this.r[4] = ((t3 >>> 4) | (t4 << 12)) & 0x00ff;
        this.r[5] = (t4 >>> 1) & 0x1ffe;
        this.r[6] = ((t4 >>> 14) | (t5 << 2)) & 0x1fff;
        this.r[7] = ((t5 >>> 11) | (t6 << 5)) & 0x1f81;
        this.r[8] = ((t6 >>> 8) | (t7 << 8)) & 0x1fff;
        this.r[9] = (t7 >>> 5) & 0x007f;
        for (let i = 0; i < 8; i++)
            this.pad[i] = u8to16(key, 16 + 2 * i);
    }
    process(data, offset, isLast = false) {
        const hibit = isLast ? 0 : 1 << 11;
        const { h, r } = this;
        const r0 = r[0];
        const r1 = r[1];
        const r2 = r[2];
        const r3 = r[3];
        const r4 = r[4];
        const r5 = r[5];
        const r6 = r[6];
        const r7 = r[7];
        const r8 = r[8];
        const r9 = r[9];
        const t0 = u8to16(data, offset + 0);
        const t1 = u8to16(data, offset + 2);
        const t2 = u8to16(data, offset + 4);
        const t3 = u8to16(data, offset + 6);
        const t4 = u8to16(data, offset + 8);
        const t5 = u8to16(data, offset + 10);
        const t6 = u8to16(data, offset + 12);
        const t7 = u8to16(data, offset + 14);
        let h0 = h[0] + (t0 & 0x1fff);
        let h1 = h[1] + (((t0 >>> 13) | (t1 << 3)) & 0x1fff);
        let h2 = h[2] + (((t1 >>> 10) | (t2 << 6)) & 0x1fff);
        let h3 = h[3] + (((t2 >>> 7) | (t3 << 9)) & 0x1fff);
        let h4 = h[4] + (((t3 >>> 4) | (t4 << 12)) & 0x1fff);
        let h5 = h[5] + ((t4 >>> 1) & 0x1fff);
        let h6 = h[6] + (((t4 >>> 14) | (t5 << 2)) & 0x1fff);
        let h7 = h[7] + (((t5 >>> 11) | (t6 << 5)) & 0x1fff);
        let h8 = h[8] + (((t6 >>> 8) | (t7 << 8)) & 0x1fff);
        let h9 = h[9] + ((t7 >>> 5) | hibit);
        let c = 0;
        let d0 = c + h0 * r0 + h1 * (5 * r9) + h2 * (5 * r8) + h3 * (5 * r7) + h4 * (5 * r6);
        c = d0 >>> 13;
        d0 &= 0x1fff;
        d0 += h5 * (5 * r5) + h6 * (5 * r4) + h7 * (5 * r3) + h8 * (5 * r2) + h9 * (5 * r1);
        c += d0 >>> 13;
        d0 &= 0x1fff;
        let d1 = c + h0 * r1 + h1 * r0 + h2 * (5 * r9) + h3 * (5 * r8) + h4 * (5 * r7);
        c = d1 >>> 13;
        d1 &= 0x1fff;
        d1 += h5 * (5 * r6) + h6 * (5 * r5) + h7 * (5 * r4) + h8 * (5 * r3) + h9 * (5 * r2);
        c += d1 >>> 13;
        d1 &= 0x1fff;
        let d2 = c + h0 * r2 + h1 * r1 + h2 * r0 + h3 * (5 * r9) + h4 * (5 * r8);
        c = d2 >>> 13;
        d2 &= 0x1fff;
        d2 += h5 * (5 * r7) + h6 * (5 * r6) + h7 * (5 * r5) + h8 * (5 * r4) + h9 * (5 * r3);
        c += d2 >>> 13;
        d2 &= 0x1fff;
        let d3 = c + h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * (5 * r9);
        c = d3 >>> 13;
        d3 &= 0x1fff;
        d3 += h5 * (5 * r8) + h6 * (5 * r7) + h7 * (5 * r6) + h8 * (5 * r5) + h9 * (5 * r4);
        c += d3 >>> 13;
        d3 &= 0x1fff;
        let d4 = c + h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;
        c = d4 >>> 13;
        d4 &= 0x1fff;
        d4 += h5 * (5 * r9) + h6 * (5 * r8) + h7 * (5 * r7) + h8 * (5 * r6) + h9 * (5 * r5);
        c += d4 >>> 13;
        d4 &= 0x1fff;
        let d5 = c + h0 * r5 + h1 * r4 + h2 * r3 + h3 * r2 + h4 * r1;
        c = d5 >>> 13;
        d5 &= 0x1fff;
        d5 += h5 * r0 + h6 * (5 * r9) + h7 * (5 * r8) + h8 * (5 * r7) + h9 * (5 * r6);
        c += d5 >>> 13;
        d5 &= 0x1fff;
        let d6 = c + h0 * r6 + h1 * r5 + h2 * r4 + h3 * r3 + h4 * r2;
        c = d6 >>> 13;
        d6 &= 0x1fff;
        d6 += h5 * r1 + h6 * r0 + h7 * (5 * r9) + h8 * (5 * r8) + h9 * (5 * r7);
        c += d6 >>> 13;
        d6 &= 0x1fff;
        let d7 = c + h0 * r7 + h1 * r6 + h2 * r5 + h3 * r4 + h4 * r3;
        c = d7 >>> 13;
        d7 &= 0x1fff;
        d7 += h5 * r2 + h6 * r1 + h7 * r0 + h8 * (5 * r9) + h9 * (5 * r8);
        c += d7 >>> 13;
        d7 &= 0x1fff;
        let d8 = c + h0 * r8 + h1 * r7 + h2 * r6 + h3 * r5 + h4 * r4;
        c = d8 >>> 13;
        d8 &= 0x1fff;
        d8 += h5 * r3 + h6 * r2 + h7 * r1 + h8 * r0 + h9 * (5 * r9);
        c += d8 >>> 13;
        d8 &= 0x1fff;
        let d9 = c + h0 * r9 + h1 * r8 + h2 * r7 + h3 * r6 + h4 * r5;
        c = d9 >>> 13;
        d9 &= 0x1fff;
        d9 += h5 * r4 + h6 * r3 + h7 * r2 + h8 * r1 + h9 * r0;
        c += d9 >>> 13;
        d9 &= 0x1fff;
        c = ((c << 2) + c) | 0;
        c = (c + d0) | 0;
        d0 = c & 0x1fff;
        c = c >>> 13;
        d1 += c;
        h[0] = d0;
        h[1] = d1;
        h[2] = d2;
        h[3] = d3;
        h[4] = d4;
        h[5] = d5;
        h[6] = d6;
        h[7] = d7;
        h[8] = d8;
        h[9] = d9;
    }
    finalize() {
        const { h, pad } = this;
        const g = new Uint16Array(10);
        let c = h[1] >>> 13;
        h[1] &= 0x1fff;
        for (let i = 2; i < 10; i++) {
            h[i] += c;
            c = h[i] >>> 13;
            h[i] &= 0x1fff;
        }
        h[0] += c * 5;
        c = h[0] >>> 13;
        h[0] &= 0x1fff;
        h[1] += c;
        c = h[1] >>> 13;
        h[1] &= 0x1fff;
        h[2] += c;
        g[0] = h[0] + 5;
        c = g[0] >>> 13;
        g[0] &= 0x1fff;
        for (let i = 1; i < 10; i++) {
            g[i] = h[i] + c;
            c = g[i] >>> 13;
            g[i] &= 0x1fff;
        }
        g[9] -= 1 << 13;
        let mask = (c ^ 1) - 1;
        for (let i = 0; i < 10; i++)
            g[i] &= mask;
        mask = ~mask;
        for (let i = 0; i < 10; i++)
            h[i] = (h[i] & mask) | g[i];
        h[0] = (h[0] | (h[1] << 13)) & 0xffff;
        h[1] = ((h[1] >>> 3) | (h[2] << 10)) & 0xffff;
        h[2] = ((h[2] >>> 6) | (h[3] << 7)) & 0xffff;
        h[3] = ((h[3] >>> 9) | (h[4] << 4)) & 0xffff;
        h[4] = ((h[4] >>> 12) | (h[5] << 1) | (h[6] << 14)) & 0xffff;
        h[5] = ((h[6] >>> 2) | (h[7] << 11)) & 0xffff;
        h[6] = ((h[7] >>> 5) | (h[8] << 8)) & 0xffff;
        h[7] = ((h[8] >>> 8) | (h[9] << 5)) & 0xffff;
        let f = h[0] + pad[0];
        h[0] = f & 0xffff;
        for (let i = 1; i < 8; i++) {
            f = (((h[i] + pad[i]) | 0) + (f >>> 16)) | 0;
            h[i] = f & 0xffff;
        }
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(g);
    }
    update(data) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        data = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(data);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(data);
        const { buffer, blockLen } = this;
        const len = data.length;
        for (let pos = 0; pos < len;) {
            const take = Math.min(blockLen - this.pos, len - pos);
            // Fast path: we have at least one block in input
            if (take === blockLen) {
                for (; blockLen <= len - pos; pos += blockLen)
                    this.process(data, pos);
                continue;
            }
            buffer.set(data.subarray(pos, pos + take), this.pos);
            this.pos += take;
            pos += take;
            if (this.pos === blockLen) {
                this.process(buffer, 0, false);
                this.pos = 0;
            }
        }
        return this;
    }
    destroy() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(this.h, this.r, this.buffer, this.pad);
    }
    digestInto(out) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aoutput)(out, this);
        this.finished = true;
        const { buffer, h } = this;
        let { pos } = this;
        if (pos) {
            buffer[pos++] = 1;
            for (; pos < 16; pos++)
                buffer[pos] = 0;
            this.process(buffer, 0, true);
        }
        this.finalize();
        let opos = 0;
        for (let i = 0; i < 8; i++) {
            out[opos++] = h[i] >>> 0;
            out[opos++] = h[i] >>> 8;
        }
        return out;
    }
    digest() {
        const { buffer, outputLen } = this;
        this.digestInto(buffer);
        const res = buffer.slice(0, outputLen);
        this.destroy();
        return res;
    }
}
function wrapConstructorWithKey(hashCons) {
    const hashC = (msg, key) => hashCons(key).update((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(msg)).digest();
    const tmp = hashCons(new Uint8Array(32));
    hashC.outputLen = tmp.outputLen;
    hashC.blockLen = tmp.blockLen;
    hashC.create = (key) => hashCons(key);
    return hashC;
}
/** Poly1305 MAC from RFC 8439. */
const poly1305 = wrapConstructorWithKey((key) => new Poly1305(key));
//# sourceMappingURL=_poly1305.js.map

/***/ },

/***/ 245
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   ghash: () => (/* binding */ ghash),
/* harmony export */   polyval: () => (/* binding */ polyval)
/* harmony export */ });
/* unused harmony export _toGHASHKey */
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(34);
/**
 * GHash from AES-GCM and its little-endian "mirror image" Polyval from AES-SIV.
 *
 * Implemented in terms of GHash with conversion function for keys
 * GCM GHASH from
 * [NIST SP800-38d](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38d.pdf),
 * SIV from
 * [RFC 8452](https://datatracker.ietf.org/doc/html/rfc8452).
 *
 * GHASH   modulo: x^128 + x^7   + x^2   + x     + 1
 * POLYVAL modulo: x^128 + x^127 + x^126 + x^121 + 1
 *
 * @module
 */
// prettier-ignore

const BLOCK_SIZE = 16;
// TODO: rewrite
// temporary padding buffer
const ZEROS16 = /* @__PURE__ */ new Uint8Array(16);
const ZEROS32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(ZEROS16);
const POLY = 0xe1; // v = 2*v % POLY
// v = 2*v % POLY
// NOTE: because x + x = 0 (add/sub is same), mul2(x) != x+x
// We can multiply any number using montgomery ladder and this function (works as double, add is simple xor)
const mul2 = (s0, s1, s2, s3) => {
    const hiBit = s3 & 1;
    return {
        s3: (s2 << 31) | (s3 >>> 1),
        s2: (s1 << 31) | (s2 >>> 1),
        s1: (s0 << 31) | (s1 >>> 1),
        s0: (s0 >>> 1) ^ ((POLY << 24) & -(hiBit & 1)), // reduce % poly
    };
};
const swapLE = (n) => (((n >>> 0) & 0xff) << 24) |
    (((n >>> 8) & 0xff) << 16) |
    (((n >>> 16) & 0xff) << 8) |
    ((n >>> 24) & 0xff) |
    0;
/**
 * `mulX_POLYVAL(ByteReverse(H))` from spec
 * @param k mutated in place
 */
function _toGHASHKey(k) {
    k.reverse();
    const hiBit = k[15] & 1;
    // k >>= 1
    let carry = 0;
    for (let i = 0; i < k.length; i++) {
        const t = k[i];
        k[i] = (t >>> 1) | carry;
        carry = (t & 1) << 7;
    }
    k[0] ^= -hiBit & 0xe1; // if (hiBit) n ^= 0xe1000000000000000000000000000000;
    return k;
}
const estimateWindow = (bytes) => {
    if (bytes > 64 * 1024)
        return 8;
    if (bytes > 1024)
        return 4;
    return 2;
};
class GHASH {
    // We select bits per window adaptively based on expectedLength
    constructor(key, expectedLength) {
        this.blockLen = BLOCK_SIZE;
        this.outputLen = BLOCK_SIZE;
        this.s0 = 0;
        this.s1 = 0;
        this.s2 = 0;
        this.s3 = 0;
        this.finished = false;
        key = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(key);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(key, 16);
        const kView = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.createView)(key);
        let k0 = kView.getUint32(0, false);
        let k1 = kView.getUint32(4, false);
        let k2 = kView.getUint32(8, false);
        let k3 = kView.getUint32(12, false);
        // generate table of doubled keys (half of montgomery ladder)
        const doubles = [];
        for (let i = 0; i < 128; i++) {
            doubles.push({ s0: swapLE(k0), s1: swapLE(k1), s2: swapLE(k2), s3: swapLE(k3) });
            ({ s0: k0, s1: k1, s2: k2, s3: k3 } = mul2(k0, k1, k2, k3));
        }
        const W = estimateWindow(expectedLength || 1024);
        if (![1, 2, 4, 8].includes(W))
            throw new Error('ghash: invalid window size, expected 2, 4 or 8');
        this.W = W;
        const bits = 128; // always 128 bits;
        const windows = bits / W;
        const windowSize = (this.windowSize = 2 ** W);
        const items = [];
        // Create precompute table for window of W bits
        for (let w = 0; w < windows; w++) {
            // truth table: 00, 01, 10, 11
            for (let byte = 0; byte < windowSize; byte++) {
                // prettier-ignore
                let s0 = 0, s1 = 0, s2 = 0, s3 = 0;
                for (let j = 0; j < W; j++) {
                    const bit = (byte >>> (W - j - 1)) & 1;
                    if (!bit)
                        continue;
                    const { s0: d0, s1: d1, s2: d2, s3: d3 } = doubles[W * w + j];
                    (s0 ^= d0), (s1 ^= d1), (s2 ^= d2), (s3 ^= d3);
                }
                items.push({ s0, s1, s2, s3 });
            }
        }
        this.t = items;
    }
    _updateBlock(s0, s1, s2, s3) {
        (s0 ^= this.s0), (s1 ^= this.s1), (s2 ^= this.s2), (s3 ^= this.s3);
        const { W, t, windowSize } = this;
        // prettier-ignore
        let o0 = 0, o1 = 0, o2 = 0, o3 = 0;
        const mask = (1 << W) - 1; // 2**W will kill performance.
        let w = 0;
        for (const num of [s0, s1, s2, s3]) {
            for (let bytePos = 0; bytePos < 4; bytePos++) {
                const byte = (num >>> (8 * bytePos)) & 0xff;
                for (let bitPos = 8 / W - 1; bitPos >= 0; bitPos--) {
                    const bit = (byte >>> (W * bitPos)) & mask;
                    const { s0: e0, s1: e1, s2: e2, s3: e3 } = t[w * windowSize + bit];
                    (o0 ^= e0), (o1 ^= e1), (o2 ^= e2), (o3 ^= e3);
                    w += 1;
                }
            }
        }
        this.s0 = o0;
        this.s1 = o1;
        this.s2 = o2;
        this.s3 = o3;
    }
    update(data) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        data = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(data);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(data);
        const b32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(data);
        const blocks = Math.floor(data.length / BLOCK_SIZE);
        const left = data.length % BLOCK_SIZE;
        for (let i = 0; i < blocks; i++) {
            this._updateBlock(b32[i * 4 + 0], b32[i * 4 + 1], b32[i * 4 + 2], b32[i * 4 + 3]);
        }
        if (left) {
            ZEROS16.set(data.subarray(blocks * BLOCK_SIZE));
            this._updateBlock(ZEROS32[0], ZEROS32[1], ZEROS32[2], ZEROS32[3]);
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(ZEROS32); // clean tmp buffer
        }
        return this;
    }
    destroy() {
        const { t } = this;
        // clean precompute table
        for (const elm of t) {
            (elm.s0 = 0), (elm.s1 = 0), (elm.s2 = 0), (elm.s3 = 0);
        }
    }
    digestInto(out) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aoutput)(out, this);
        this.finished = true;
        const { s0, s1, s2, s3 } = this;
        const o32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(out);
        o32[0] = s0;
        o32[1] = s1;
        o32[2] = s2;
        o32[3] = s3;
        return out;
    }
    digest() {
        const res = new Uint8Array(BLOCK_SIZE);
        this.digestInto(res);
        this.destroy();
        return res;
    }
}
class Polyval extends GHASH {
    constructor(key, expectedLength) {
        key = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(key);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(key);
        const ghKey = _toGHASHKey((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.copyBytes)(key));
        super(ghKey, expectedLength);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(ghKey);
    }
    update(data) {
        data = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(data);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        const b32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(data);
        const left = data.length % BLOCK_SIZE;
        const blocks = Math.floor(data.length / BLOCK_SIZE);
        for (let i = 0; i < blocks; i++) {
            this._updateBlock(swapLE(b32[i * 4 + 3]), swapLE(b32[i * 4 + 2]), swapLE(b32[i * 4 + 1]), swapLE(b32[i * 4 + 0]));
        }
        if (left) {
            ZEROS16.set(data.subarray(blocks * BLOCK_SIZE));
            this._updateBlock(swapLE(ZEROS32[3]), swapLE(ZEROS32[2]), swapLE(ZEROS32[1]), swapLE(ZEROS32[0]));
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(ZEROS32);
        }
        return this;
    }
    digestInto(out) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aoutput)(out, this);
        this.finished = true;
        // tmp ugly hack
        const { s0, s1, s2, s3 } = this;
        const o32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.u32)(out);
        o32[0] = s0;
        o32[1] = s1;
        o32[2] = s2;
        o32[3] = s3;
        return out.reverse();
    }
}
function wrapConstructorWithKey(hashCons) {
    const hashC = (msg, key) => hashCons(key, msg.length).update((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(msg)).digest();
    const tmp = hashCons(new Uint8Array(16), 0);
    hashC.outputLen = tmp.outputLen;
    hashC.blockLen = tmp.blockLen;
    hashC.create = (key, expectedLength) => hashCons(key, expectedLength);
    return hashC;
}
/** GHash MAC for AES-GCM. */
const ghash = wrapConstructorWithKey((key, expectedLength) => new GHASH(key, expectedLength));
/** Polyval MAC for AES-SIV. */
const polyval = wrapConstructorWithKey((key, expectedLength) => new Polyval(key, expectedLength));
//# sourceMappingURL=_polyval.js.map

/***/ },

/***/ 60
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   cbc: () => (/* binding */ cbc)
/* harmony export */ });
/* unused harmony exports ctr, ecb, cfb, gcm, gcmsiv, siv, aeskw, aeskwp, unsafe */
/* harmony import */ var _polyval_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(245);
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(34);
/**
 * [AES](https://en.wikipedia.org/wiki/Advanced_Encryption_Standard)
 * a.k.a. Advanced Encryption Standard
 * is a variant of Rijndael block cipher, standardized by NIST in 2001.
 * We provide the fastest available pure JS implementation.
 *
 * Data is split into 128-bit blocks. Encrypted in 10/12/14 rounds (128/192/256 bits). In every round:
 * 1. **S-box**, table substitution
 * 2. **Shift rows**, cyclic shift left of all rows of data array
 * 3. **Mix columns**, multiplying every column by fixed polynomial
 * 4. **Add round key**, round_key xor i-th column of array
 *
 * Check out [FIPS-197](https://csrc.nist.gov/files/pubs/fips/197/final/docs/fips-197.pdf)
 * and [original proposal](https://csrc.nist.gov/csrc/media/projects/cryptographic-standards-and-guidelines/documents/aes-development/rijndael-ammended.pdf)
 * @module
 */

// prettier-ignore

const BLOCK_SIZE = 16;
const BLOCK_SIZE32 = 4;
const EMPTY_BLOCK = /* @__PURE__ */ new Uint8Array(BLOCK_SIZE);
const POLY = 0x11b; // 1 + x + x**3 + x**4 + x**8
// TODO: remove multiplication, binary ops only
function mul2(n) {
    return (n << 1) ^ (POLY & -(n >> 7));
}
function mul(a, b) {
    let res = 0;
    for (; b > 0; b >>= 1) {
        // Montgomery ladder
        res ^= a & -(b & 1); // if (b&1) res ^=a (but const-time).
        a = mul2(a); // a = 2*a
    }
    return res;
}
// AES S-box is generated using finite field inversion,
// an affine transform, and xor of a constant 0x63.
const sbox = /* @__PURE__ */ (() => {
    const t = new Uint8Array(256);
    for (let i = 0, x = 1; i < 256; i++, x ^= mul2(x))
        t[i] = x;
    const box = new Uint8Array(256);
    box[0] = 0x63; // first elm
    for (let i = 0; i < 255; i++) {
        let x = t[255 - i];
        x |= x << 8;
        box[t[i]] = (x ^ (x >> 4) ^ (x >> 5) ^ (x >> 6) ^ (x >> 7) ^ 0x63) & 0xff;
    }
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(t);
    return box;
})();
// Inverted S-box
const invSbox = /* @__PURE__ */ sbox.map((_, j) => sbox.indexOf(j));
// Rotate u32 by 8
const rotr32_8 = (n) => (n << 24) | (n >>> 8);
const rotl32_8 = (n) => (n << 8) | (n >>> 24);
// The byte swap operation for uint32 (LE<->BE)
const byteSwap = (word) => ((word << 24) & 0xff000000) |
    ((word << 8) & 0xff0000) |
    ((word >>> 8) & 0xff00) |
    ((word >>> 24) & 0xff);
// T-table is optimization suggested in 5.2 of original proposal (missed from FIPS-197). Changes:
// - LE instead of BE
// - bigger tables: T0 and T1 are merged into T01 table and T2 & T3 into T23;
//   so index is u16, instead of u8. This speeds up things, unexpectedly
function genTtable(sbox, fn) {
    if (sbox.length !== 256)
        throw new Error('Wrong sbox length');
    const T0 = new Uint32Array(256).map((_, j) => fn(sbox[j]));
    const T1 = T0.map(rotl32_8);
    const T2 = T1.map(rotl32_8);
    const T3 = T2.map(rotl32_8);
    const T01 = new Uint32Array(256 * 256);
    const T23 = new Uint32Array(256 * 256);
    const sbox2 = new Uint16Array(256 * 256);
    for (let i = 0; i < 256; i++) {
        for (let j = 0; j < 256; j++) {
            const idx = i * 256 + j;
            T01[idx] = T0[i] ^ T1[j];
            T23[idx] = T2[i] ^ T3[j];
            sbox2[idx] = (sbox[i] << 8) | sbox[j];
        }
    }
    return { sbox, sbox2, T0, T1, T2, T3, T01, T23 };
}
const tableEncoding = /* @__PURE__ */ genTtable(sbox, (s) => (mul(s, 3) << 24) | (s << 16) | (s << 8) | mul(s, 2));
const tableDecoding = /* @__PURE__ */ genTtable(invSbox, (s) => (mul(s, 11) << 24) | (mul(s, 13) << 16) | (mul(s, 9) << 8) | mul(s, 14));
const xPowers = /* @__PURE__ */ (() => {
    const p = new Uint8Array(16);
    for (let i = 0, x = 1; i < 16; i++, x = mul2(x))
        p[i] = x;
    return p;
})();
/** Key expansion used in CTR. */
function expandKeyLE(key) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(key);
    const len = key.length;
    if (![16, 24, 32].includes(len))
        throw new Error('aes: invalid key size, should be 16, 24 or 32, got ' + len);
    const { sbox2 } = tableEncoding;
    const toClean = [];
    if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(key))
        toClean.push((key = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(key)));
    const k32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(key);
    const Nk = k32.length;
    const subByte = (n) => applySbox(sbox2, n, n, n, n);
    const xk = new Uint32Array(len + 28); // expanded key
    xk.set(k32);
    // 4.3.1 Key expansion
    for (let i = Nk; i < xk.length; i++) {
        let t = xk[i - 1];
        if (i % Nk === 0)
            t = subByte(rotr32_8(t)) ^ xPowers[i / Nk - 1];
        else if (Nk > 6 && i % Nk === 4)
            t = subByte(t);
        xk[i] = xk[i - Nk] ^ t;
    }
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
    return xk;
}
function expandKeyDecLE(key) {
    const encKey = expandKeyLE(key);
    const xk = encKey.slice();
    const Nk = encKey.length;
    const { sbox2 } = tableEncoding;
    const { T0, T1, T2, T3 } = tableDecoding;
    // Inverse key by chunks of 4 (rounds)
    for (let i = 0; i < Nk; i += 4) {
        for (let j = 0; j < 4; j++)
            xk[i + j] = encKey[Nk - i - 4 + j];
    }
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(encKey);
    // apply InvMixColumn except first & last round
    for (let i = 4; i < Nk - 4; i++) {
        const x = xk[i];
        const w = applySbox(sbox2, x, x, x, x);
        xk[i] = T0[w & 0xff] ^ T1[(w >>> 8) & 0xff] ^ T2[(w >>> 16) & 0xff] ^ T3[w >>> 24];
    }
    return xk;
}
// Apply tables
function apply0123(T01, T23, s0, s1, s2, s3) {
    return (T01[((s0 << 8) & 0xff00) | ((s1 >>> 8) & 0xff)] ^
        T23[((s2 >>> 8) & 0xff00) | ((s3 >>> 24) & 0xff)]);
}
function applySbox(sbox2, s0, s1, s2, s3) {
    return (sbox2[(s0 & 0xff) | (s1 & 0xff00)] |
        (sbox2[((s2 >>> 16) & 0xff) | ((s3 >>> 16) & 0xff00)] << 16));
}
function encrypt(xk, s0, s1, s2, s3) {
    const { sbox2, T01, T23 } = tableEncoding;
    let k = 0;
    (s0 ^= xk[k++]), (s1 ^= xk[k++]), (s2 ^= xk[k++]), (s3 ^= xk[k++]);
    const rounds = xk.length / 4 - 2;
    for (let i = 0; i < rounds; i++) {
        const t0 = xk[k++] ^ apply0123(T01, T23, s0, s1, s2, s3);
        const t1 = xk[k++] ^ apply0123(T01, T23, s1, s2, s3, s0);
        const t2 = xk[k++] ^ apply0123(T01, T23, s2, s3, s0, s1);
        const t3 = xk[k++] ^ apply0123(T01, T23, s3, s0, s1, s2);
        (s0 = t0), (s1 = t1), (s2 = t2), (s3 = t3);
    }
    // last round (without mixcolumns, so using SBOX2 table)
    const t0 = xk[k++] ^ applySbox(sbox2, s0, s1, s2, s3);
    const t1 = xk[k++] ^ applySbox(sbox2, s1, s2, s3, s0);
    const t2 = xk[k++] ^ applySbox(sbox2, s2, s3, s0, s1);
    const t3 = xk[k++] ^ applySbox(sbox2, s3, s0, s1, s2);
    return { s0: t0, s1: t1, s2: t2, s3: t3 };
}
// Can't be merged with encrypt: arg positions for apply0123 / applySbox are different
function decrypt(xk, s0, s1, s2, s3) {
    const { sbox2, T01, T23 } = tableDecoding;
    let k = 0;
    (s0 ^= xk[k++]), (s1 ^= xk[k++]), (s2 ^= xk[k++]), (s3 ^= xk[k++]);
    const rounds = xk.length / 4 - 2;
    for (let i = 0; i < rounds; i++) {
        const t0 = xk[k++] ^ apply0123(T01, T23, s0, s3, s2, s1);
        const t1 = xk[k++] ^ apply0123(T01, T23, s1, s0, s3, s2);
        const t2 = xk[k++] ^ apply0123(T01, T23, s2, s1, s0, s3);
        const t3 = xk[k++] ^ apply0123(T01, T23, s3, s2, s1, s0);
        (s0 = t0), (s1 = t1), (s2 = t2), (s3 = t3);
    }
    // Last round
    const t0 = xk[k++] ^ applySbox(sbox2, s0, s3, s2, s1);
    const t1 = xk[k++] ^ applySbox(sbox2, s1, s0, s3, s2);
    const t2 = xk[k++] ^ applySbox(sbox2, s2, s1, s0, s3);
    const t3 = xk[k++] ^ applySbox(sbox2, s3, s2, s1, s0);
    return { s0: t0, s1: t1, s2: t2, s3: t3 };
}
// TODO: investigate merging with ctr32
function ctrCounter(xk, nonce, src, dst) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(nonce, BLOCK_SIZE);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(src);
    const srcLen = src.length;
    dst = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.getOutput)(srcLen, dst);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.complexOverlapBytes)(src, dst);
    const ctr = nonce;
    const c32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(ctr);
    // Fill block (empty, ctr=0)
    let { s0, s1, s2, s3 } = encrypt(xk, c32[0], c32[1], c32[2], c32[3]);
    const src32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(src);
    const dst32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(dst);
    // process blocks
    for (let i = 0; i + 4 <= src32.length; i += 4) {
        dst32[i + 0] = src32[i + 0] ^ s0;
        dst32[i + 1] = src32[i + 1] ^ s1;
        dst32[i + 2] = src32[i + 2] ^ s2;
        dst32[i + 3] = src32[i + 3] ^ s3;
        // Full 128 bit counter with wrap around
        let carry = 1;
        for (let i = ctr.length - 1; i >= 0; i--) {
            carry = (carry + (ctr[i] & 0xff)) | 0;
            ctr[i] = carry & 0xff;
            carry >>>= 8;
        }
        ({ s0, s1, s2, s3 } = encrypt(xk, c32[0], c32[1], c32[2], c32[3]));
    }
    // leftovers (less than block)
    // It's possible to handle > u32 fast, but is it worth it?
    const start = BLOCK_SIZE * Math.floor(src32.length / BLOCK_SIZE32);
    if (start < srcLen) {
        const b32 = new Uint32Array([s0, s1, s2, s3]);
        const buf = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u8)(b32);
        for (let i = start, pos = 0; i < srcLen; i++, pos++)
            dst[i] = src[i] ^ buf[pos];
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(b32);
    }
    return dst;
}
// AES CTR with overflowing 32 bit counter
// It's possible to do 32le significantly simpler (and probably faster) by using u32.
// But, we need both, and perf bottleneck is in ghash anyway.
function ctr32(xk, isLE, nonce, src, dst) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(nonce, BLOCK_SIZE);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(src);
    dst = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.getOutput)(src.length, dst);
    const ctr = nonce; // write new value to nonce, so it can be re-used
    const c32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(ctr);
    const view = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createView)(ctr);
    const src32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(src);
    const dst32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(dst);
    const ctrPos = isLE ? 0 : 12;
    const srcLen = src.length;
    // Fill block (empty, ctr=0)
    let ctrNum = view.getUint32(ctrPos, isLE); // read current counter value
    let { s0, s1, s2, s3 } = encrypt(xk, c32[0], c32[1], c32[2], c32[3]);
    // process blocks
    for (let i = 0; i + 4 <= src32.length; i += 4) {
        dst32[i + 0] = src32[i + 0] ^ s0;
        dst32[i + 1] = src32[i + 1] ^ s1;
        dst32[i + 2] = src32[i + 2] ^ s2;
        dst32[i + 3] = src32[i + 3] ^ s3;
        ctrNum = (ctrNum + 1) >>> 0; // u32 wrap
        view.setUint32(ctrPos, ctrNum, isLE);
        ({ s0, s1, s2, s3 } = encrypt(xk, c32[0], c32[1], c32[2], c32[3]));
    }
    // leftovers (less than a block)
    const start = BLOCK_SIZE * Math.floor(src32.length / BLOCK_SIZE32);
    if (start < srcLen) {
        const b32 = new Uint32Array([s0, s1, s2, s3]);
        const buf = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u8)(b32);
        for (let i = start, pos = 0; i < srcLen; i++, pos++)
            dst[i] = src[i] ^ buf[pos];
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(b32);
    }
    return dst;
}
/**
 * CTR: counter mode. Creates stream cipher.
 * Requires good IV. Parallelizable. OK, but no MAC.
 */
const ctr = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.wrapCipher)({ blockSize: 16, nonceLength: 16 }, function aesctr(key, nonce) {
    function processCtr(buf, dst) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(buf);
        if (dst !== undefined) {
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(dst);
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(dst))
                throw new Error('unaligned destination');
        }
        const xk = expandKeyLE(key);
        const n = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(nonce); // align + avoid changing
        const toClean = [xk, n];
        if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(buf))
            toClean.push((buf = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(buf)));
        const out = ctrCounter(xk, n, buf, dst);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
        return out;
    }
    return {
        encrypt: (plaintext, dst) => processCtr(plaintext, dst),
        decrypt: (ciphertext, dst) => processCtr(ciphertext, dst),
    };
});
function validateBlockDecrypt(data) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(data);
    if (data.length % BLOCK_SIZE !== 0) {
        throw new Error('aes-(cbc/ecb).decrypt ciphertext should consist of blocks with size ' + BLOCK_SIZE);
    }
}
function validateBlockEncrypt(plaintext, pcks5, dst) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(plaintext);
    let outLen = plaintext.length;
    const remaining = outLen % BLOCK_SIZE;
    if (!pcks5 && remaining !== 0)
        throw new Error('aec/(cbc-ecb): unpadded plaintext with disabled padding');
    if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(plaintext))
        plaintext = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(plaintext);
    const b = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(plaintext);
    if (pcks5) {
        let left = BLOCK_SIZE - remaining;
        if (!left)
            left = BLOCK_SIZE; // if no bytes left, create empty padding block
        outLen = outLen + left;
    }
    dst = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.getOutput)(outLen, dst);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.complexOverlapBytes)(plaintext, dst);
    const o = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(dst);
    return { b, o, out: dst };
}
function validatePCKS(data, pcks5) {
    if (!pcks5)
        return data;
    const len = data.length;
    if (!len)
        throw new Error('aes/pcks5: empty ciphertext not allowed');
    const lastByte = data[len - 1];
    if (lastByte <= 0 || lastByte > 16)
        throw new Error('aes/pcks5: wrong padding');
    const out = data.subarray(0, -lastByte);
    for (let i = 0; i < lastByte; i++)
        if (data[len - i - 1] !== lastByte)
            throw new Error('aes/pcks5: wrong padding');
    return out;
}
function padPCKS(left) {
    const tmp = new Uint8Array(16);
    const tmp32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(tmp);
    tmp.set(left);
    const paddingByte = BLOCK_SIZE - left.length;
    for (let i = BLOCK_SIZE - paddingByte; i < BLOCK_SIZE; i++)
        tmp[i] = paddingByte;
    return tmp32;
}
/**
 * ECB: Electronic CodeBook. Simple deterministic replacement.
 * Dangerous: always map x to y. See [AES Penguin](https://words.filippo.io/the-ecb-penguin/).
 */
const ecb = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.wrapCipher)({ blockSize: 16 }, function aesecb(key, opts = {}) {
    const pcks5 = !opts.disablePadding;
    return {
        encrypt(plaintext, dst) {
            const { b, o, out: _out } = validateBlockEncrypt(plaintext, pcks5, dst);
            const xk = expandKeyLE(key);
            let i = 0;
            for (; i + 4 <= b.length;) {
                const { s0, s1, s2, s3 } = encrypt(xk, b[i + 0], b[i + 1], b[i + 2], b[i + 3]);
                (o[i++] = s0), (o[i++] = s1), (o[i++] = s2), (o[i++] = s3);
            }
            if (pcks5) {
                const tmp32 = padPCKS(plaintext.subarray(i * 4));
                const { s0, s1, s2, s3 } = encrypt(xk, tmp32[0], tmp32[1], tmp32[2], tmp32[3]);
                (o[i++] = s0), (o[i++] = s1), (o[i++] = s2), (o[i++] = s3);
            }
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(xk);
            return _out;
        },
        decrypt(ciphertext, dst) {
            validateBlockDecrypt(ciphertext);
            const xk = expandKeyDecLE(key);
            dst = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.getOutput)(ciphertext.length, dst);
            const toClean = [xk];
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(ciphertext))
                toClean.push((ciphertext = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(ciphertext)));
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.complexOverlapBytes)(ciphertext, dst);
            const b = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(ciphertext);
            const o = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(dst);
            for (let i = 0; i + 4 <= b.length;) {
                const { s0, s1, s2, s3 } = decrypt(xk, b[i + 0], b[i + 1], b[i + 2], b[i + 3]);
                (o[i++] = s0), (o[i++] = s1), (o[i++] = s2), (o[i++] = s3);
            }
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
            return validatePCKS(dst, pcks5);
        },
    };
});
/**
 * CBC: Cipher-Block-Chaining. Key is previous round’s block.
 * Fragile: needs proper padding. Unauthenticated: needs MAC.
 */
const cbc = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.wrapCipher)({ blockSize: 16, nonceLength: 16 }, function aescbc(key, iv, opts = {}) {
    const pcks5 = !opts.disablePadding;
    return {
        encrypt(plaintext, dst) {
            const xk = expandKeyLE(key);
            const { b, o, out: _out } = validateBlockEncrypt(plaintext, pcks5, dst);
            let _iv = iv;
            const toClean = [xk];
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(_iv))
                toClean.push((_iv = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(_iv)));
            const n32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(_iv);
            // prettier-ignore
            let s0 = n32[0], s1 = n32[1], s2 = n32[2], s3 = n32[3];
            let i = 0;
            for (; i + 4 <= b.length;) {
                (s0 ^= b[i + 0]), (s1 ^= b[i + 1]), (s2 ^= b[i + 2]), (s3 ^= b[i + 3]);
                ({ s0, s1, s2, s3 } = encrypt(xk, s0, s1, s2, s3));
                (o[i++] = s0), (o[i++] = s1), (o[i++] = s2), (o[i++] = s3);
            }
            if (pcks5) {
                const tmp32 = padPCKS(plaintext.subarray(i * 4));
                (s0 ^= tmp32[0]), (s1 ^= tmp32[1]), (s2 ^= tmp32[2]), (s3 ^= tmp32[3]);
                ({ s0, s1, s2, s3 } = encrypt(xk, s0, s1, s2, s3));
                (o[i++] = s0), (o[i++] = s1), (o[i++] = s2), (o[i++] = s3);
            }
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
            return _out;
        },
        decrypt(ciphertext, dst) {
            validateBlockDecrypt(ciphertext);
            const xk = expandKeyDecLE(key);
            let _iv = iv;
            const toClean = [xk];
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(_iv))
                toClean.push((_iv = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(_iv)));
            const n32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(_iv);
            dst = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.getOutput)(ciphertext.length, dst);
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(ciphertext))
                toClean.push((ciphertext = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(ciphertext)));
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.complexOverlapBytes)(ciphertext, dst);
            const b = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(ciphertext);
            const o = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(dst);
            // prettier-ignore
            let s0 = n32[0], s1 = n32[1], s2 = n32[2], s3 = n32[3];
            for (let i = 0; i + 4 <= b.length;) {
                // prettier-ignore
                const ps0 = s0, ps1 = s1, ps2 = s2, ps3 = s3;
                (s0 = b[i + 0]), (s1 = b[i + 1]), (s2 = b[i + 2]), (s3 = b[i + 3]);
                const { s0: o0, s1: o1, s2: o2, s3: o3 } = decrypt(xk, s0, s1, s2, s3);
                (o[i++] = o0 ^ ps0), (o[i++] = o1 ^ ps1), (o[i++] = o2 ^ ps2), (o[i++] = o3 ^ ps3);
            }
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
            return validatePCKS(dst, pcks5);
        },
    };
});
/**
 * CFB: Cipher Feedback Mode. The input for the block cipher is the previous cipher output.
 * Unauthenticated: needs MAC.
 */
const cfb = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.wrapCipher)({ blockSize: 16, nonceLength: 16 }, function aescfb(key, iv) {
    function processCfb(src, isEncrypt, dst) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(src);
        const srcLen = src.length;
        dst = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.getOutput)(srcLen, dst);
        if ((0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.overlapBytes)(src, dst))
            throw new Error('overlapping src and dst not supported.');
        const xk = expandKeyLE(key);
        let _iv = iv;
        const toClean = [xk];
        if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(_iv))
            toClean.push((_iv = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(_iv)));
        if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(src))
            toClean.push((src = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(src)));
        const src32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(src);
        const dst32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(dst);
        const next32 = isEncrypt ? dst32 : src32;
        const n32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(_iv);
        // prettier-ignore
        let s0 = n32[0], s1 = n32[1], s2 = n32[2], s3 = n32[3];
        for (let i = 0; i + 4 <= src32.length;) {
            const { s0: e0, s1: e1, s2: e2, s3: e3 } = encrypt(xk, s0, s1, s2, s3);
            dst32[i + 0] = src32[i + 0] ^ e0;
            dst32[i + 1] = src32[i + 1] ^ e1;
            dst32[i + 2] = src32[i + 2] ^ e2;
            dst32[i + 3] = src32[i + 3] ^ e3;
            (s0 = next32[i++]), (s1 = next32[i++]), (s2 = next32[i++]), (s3 = next32[i++]);
        }
        // leftovers (less than block)
        const start = BLOCK_SIZE * Math.floor(src32.length / BLOCK_SIZE32);
        if (start < srcLen) {
            ({ s0, s1, s2, s3 } = encrypt(xk, s0, s1, s2, s3));
            const buf = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u8)(new Uint32Array([s0, s1, s2, s3]));
            for (let i = start, pos = 0; i < srcLen; i++, pos++)
                dst[i] = src[i] ^ buf[pos];
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(buf);
        }
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
        return dst;
    }
    return {
        encrypt: (plaintext, dst) => processCfb(plaintext, true, dst),
        decrypt: (ciphertext, dst) => processCfb(ciphertext, false, dst),
    };
});
// TODO: merge with chacha, however gcm has bitLen while chacha has byteLen
function computeTag(fn, isLE, key, data, AAD) {
    const aadLength = AAD ? AAD.length : 0;
    const h = fn.create(key, data.length + aadLength);
    if (AAD)
        h.update(AAD);
    const num = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u64Lengths)(8 * data.length, 8 * aadLength, isLE);
    h.update(data);
    h.update(num);
    const res = h.digest();
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(num);
    return res;
}
/**
 * GCM: Galois/Counter Mode.
 * Modern, parallel version of CTR, with MAC.
 * Be careful: MACs can be forged.
 * Unsafe to use random nonces under the same key, due to collision chance.
 * As for nonce size, prefer 12-byte, instead of 8-byte.
 */
const gcm = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.wrapCipher)({ blockSize: 16, nonceLength: 12, tagLength: 16, varSizeNonce: true }, function aesgcm(key, nonce, AAD) {
    // NIST 800-38d doesn't enforce minimum nonce length.
    // We enforce 8 bytes for compat with openssl.
    // 12 bytes are recommended. More than 12 bytes would be converted into 12.
    if (nonce.length < 8)
        throw new Error('aes/gcm: invalid nonce length');
    const tagLength = 16;
    function _computeTag(authKey, tagMask, data) {
        const tag = computeTag(_polyval_js__WEBPACK_IMPORTED_MODULE_0__.ghash, false, authKey, data, AAD);
        for (let i = 0; i < tagMask.length; i++)
            tag[i] ^= tagMask[i];
        return tag;
    }
    function deriveKeys() {
        const xk = expandKeyLE(key);
        const authKey = EMPTY_BLOCK.slice();
        const counter = EMPTY_BLOCK.slice();
        ctr32(xk, false, counter, counter, authKey);
        // NIST 800-38d, page 15: different behavior for 96-bit and non-96-bit nonces
        if (nonce.length === 12) {
            counter.set(nonce);
        }
        else {
            const nonceLen = EMPTY_BLOCK.slice();
            const view = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createView)(nonceLen);
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.setBigUint64)(view, 8, BigInt(nonce.length * 8), false);
            // ghash(nonce || u64be(0) || u64be(nonceLen*8))
            const g = _polyval_js__WEBPACK_IMPORTED_MODULE_0__.ghash.create(authKey).update(nonce).update(nonceLen);
            g.digestInto(counter); // digestInto doesn't trigger '.destroy'
            g.destroy();
        }
        const tagMask = ctr32(xk, false, counter, EMPTY_BLOCK);
        return { xk, authKey, counter, tagMask };
    }
    return {
        encrypt(plaintext) {
            const { xk, authKey, counter, tagMask } = deriveKeys();
            const out = new Uint8Array(plaintext.length + tagLength);
            const toClean = [xk, authKey, counter, tagMask];
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(plaintext))
                toClean.push((plaintext = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(plaintext)));
            ctr32(xk, false, counter, plaintext, out.subarray(0, plaintext.length));
            const tag = _computeTag(authKey, tagMask, out.subarray(0, out.length - tagLength));
            toClean.push(tag);
            out.set(tag, plaintext.length);
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
            return out;
        },
        decrypt(ciphertext) {
            const { xk, authKey, counter, tagMask } = deriveKeys();
            const toClean = [xk, authKey, tagMask, counter];
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(ciphertext))
                toClean.push((ciphertext = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(ciphertext)));
            const data = ciphertext.subarray(0, -tagLength);
            const passedTag = ciphertext.subarray(-tagLength);
            const tag = _computeTag(authKey, tagMask, data);
            toClean.push(tag);
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.equalBytes)(tag, passedTag))
                throw new Error('aes/gcm: invalid ghash tag');
            const out = ctr32(xk, false, counter, data);
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
            return out;
        },
    };
});
const limit = (name, min, max) => (value) => {
    if (!Number.isSafeInteger(value) || min > value || value > max) {
        const minmax = '[' + min + '..' + max + ']';
        throw new Error('' + name + ': expected value in range ' + minmax + ', got ' + value);
    }
};
/**
 * AES-GCM-SIV: classic AES-GCM with nonce-misuse resistance.
 * Guarantees that, when a nonce is repeated, the only security loss is that identical
 * plaintexts will produce identical ciphertexts.
 * RFC 8452, https://datatracker.ietf.org/doc/html/rfc8452
 */
const gcmsiv = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.wrapCipher)({ blockSize: 16, nonceLength: 12, tagLength: 16, varSizeNonce: true }, function aessiv(key, nonce, AAD) {
    const tagLength = 16;
    // From RFC 8452: Section 6
    const AAD_LIMIT = limit('AAD', 0, 2 ** 36);
    const PLAIN_LIMIT = limit('plaintext', 0, 2 ** 36);
    const NONCE_LIMIT = limit('nonce', 12, 12);
    const CIPHER_LIMIT = limit('ciphertext', 16, 2 ** 36 + 16);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(key, 16, 24, 32);
    NONCE_LIMIT(nonce.length);
    if (AAD !== undefined)
        AAD_LIMIT(AAD.length);
    function deriveKeys() {
        const xk = expandKeyLE(key);
        const encKey = new Uint8Array(key.length);
        const authKey = new Uint8Array(16);
        const toClean = [xk, encKey];
        let _nonce = nonce;
        if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(_nonce))
            toClean.push((_nonce = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(_nonce)));
        const n32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(_nonce);
        // prettier-ignore
        let s0 = 0, s1 = n32[0], s2 = n32[1], s3 = n32[2];
        let counter = 0;
        for (const derivedKey of [authKey, encKey].map(_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)) {
            const d32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(derivedKey);
            for (let i = 0; i < d32.length; i += 2) {
                // aes(u32le(0) || nonce)[:8] || aes(u32le(1) || nonce)[:8] ...
                const { s0: o0, s1: o1 } = encrypt(xk, s0, s1, s2, s3);
                d32[i + 0] = o0;
                d32[i + 1] = o1;
                s0 = ++counter; // increment counter inside state
            }
        }
        const res = { authKey, encKey: expandKeyLE(encKey) };
        // Cleanup
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
        return res;
    }
    function _computeTag(encKey, authKey, data) {
        const tag = computeTag(_polyval_js__WEBPACK_IMPORTED_MODULE_0__.polyval, true, authKey, data, AAD);
        // Compute the expected tag by XORing S_s and the nonce, clearing the
        // most significant bit of the last byte and encrypting with the
        // message-encryption key.
        for (let i = 0; i < 12; i++)
            tag[i] ^= nonce[i];
        tag[15] &= 0x7f; // Clear the highest bit
        // encrypt tag as block
        const t32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(tag);
        // prettier-ignore
        let s0 = t32[0], s1 = t32[1], s2 = t32[2], s3 = t32[3];
        ({ s0, s1, s2, s3 } = encrypt(encKey, s0, s1, s2, s3));
        (t32[0] = s0), (t32[1] = s1), (t32[2] = s2), (t32[3] = s3);
        return tag;
    }
    // actual decrypt/encrypt of message.
    function processSiv(encKey, tag, input) {
        let block = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(tag);
        block[15] |= 0x80; // Force highest bit
        const res = ctr32(encKey, true, block, input);
        // Cleanup
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(block);
        return res;
    }
    return {
        encrypt(plaintext) {
            PLAIN_LIMIT(plaintext.length);
            const { encKey, authKey } = deriveKeys();
            const tag = _computeTag(encKey, authKey, plaintext);
            const toClean = [encKey, authKey, tag];
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(plaintext))
                toClean.push((plaintext = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(plaintext)));
            const out = new Uint8Array(plaintext.length + tagLength);
            out.set(tag, plaintext.length);
            out.set(processSiv(encKey, tag, plaintext));
            // Cleanup
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
            return out;
        },
        decrypt(ciphertext) {
            CIPHER_LIMIT(ciphertext.length);
            const tag = ciphertext.subarray(-tagLength);
            const { encKey, authKey } = deriveKeys();
            const toClean = [encKey, authKey];
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.isAligned32)(ciphertext))
                toClean.push((ciphertext = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(ciphertext)));
            const plaintext = processSiv(encKey, tag, ciphertext.subarray(0, -tagLength));
            const expectedTag = _computeTag(encKey, authKey, plaintext);
            toClean.push(expectedTag);
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.equalBytes)(tag, expectedTag)) {
                (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
                throw new Error('invalid polyval tag');
            }
            // Cleanup
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(...toClean);
            return plaintext;
        },
    };
});
/**
 * AES-GCM-SIV, not AES-SIV.
 * This is legace name, use `gcmsiv` export instead.
 * @deprecated
 */
const siv = (/* unused pure expression or super */ null && (gcmsiv));
function isBytes32(a) {
    return (a instanceof Uint32Array || (ArrayBuffer.isView(a) && a.constructor.name === 'Uint32Array'));
}
function encryptBlock(xk, block) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(block, 16);
    if (!isBytes32(xk))
        throw new Error('_encryptBlock accepts result of expandKeyLE');
    const b32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(block);
    let { s0, s1, s2, s3 } = encrypt(xk, b32[0], b32[1], b32[2], b32[3]);
    (b32[0] = s0), (b32[1] = s1), (b32[2] = s2), (b32[3] = s3);
    return block;
}
function decryptBlock(xk, block) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.abytes)(block, 16);
    if (!isBytes32(xk))
        throw new Error('_decryptBlock accepts result of expandKeyLE');
    const b32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(block);
    let { s0, s1, s2, s3 } = decrypt(xk, b32[0], b32[1], b32[2], b32[3]);
    (b32[0] = s0), (b32[1] = s1), (b32[2] = s2), (b32[3] = s3);
    return block;
}
/**
 * AES-W (base for AESKW/AESKWP).
 * Specs: [SP800-38F](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-38F.pdf),
 * [RFC 3394](https://datatracker.ietf.org/doc/rfc3394/),
 * [RFC 5649](https://datatracker.ietf.org/doc/rfc5649/).
 */
const AESW = {
    /*
    High-level pseudocode:
    ```
    A: u64 = IV
    out = []
    for (let i=0, ctr = 0; i<6; i++) {
      for (const chunk of chunks(plaintext, 8)) {
        A ^= swapEndianess(ctr++)
        [A, res] = chunks(encrypt(A || chunk), 8);
        out ||= res
      }
    }
    out = A || out
    ```
    Decrypt is the same, but reversed.
    */
    encrypt(kek, out) {
        // Size is limited to 4GB, otherwise ctr will overflow and we'll need to switch to bigints.
        // If you need it larger, open an issue.
        if (out.length >= 2 ** 32)
            throw new Error('plaintext should be less than 4gb');
        const xk = expandKeyLE(kek);
        if (out.length === 16)
            encryptBlock(xk, out);
        else {
            const o32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(out);
            // prettier-ignore
            let a0 = o32[0], a1 = o32[1]; // A
            for (let j = 0, ctr = 1; j < 6; j++) {
                for (let pos = 2; pos < o32.length; pos += 2, ctr++) {
                    const { s0, s1, s2, s3 } = encrypt(xk, a0, a1, o32[pos], o32[pos + 1]);
                    // A = MSB(64, B) ^ t where t = (n*j)+i
                    (a0 = s0), (a1 = s1 ^ byteSwap(ctr)), (o32[pos] = s2), (o32[pos + 1] = s3);
                }
            }
            (o32[0] = a0), (o32[1] = a1); // out = A || out
        }
        xk.fill(0);
    },
    decrypt(kek, out) {
        if (out.length - 8 >= 2 ** 32)
            throw new Error('ciphertext should be less than 4gb');
        const xk = expandKeyDecLE(kek);
        const chunks = out.length / 8 - 1; // first chunk is IV
        if (chunks === 1)
            decryptBlock(xk, out);
        else {
            const o32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(out);
            // prettier-ignore
            let a0 = o32[0], a1 = o32[1]; // A
            for (let j = 0, ctr = chunks * 6; j < 6; j++) {
                for (let pos = chunks * 2; pos >= 1; pos -= 2, ctr--) {
                    a1 ^= byteSwap(ctr);
                    const { s0, s1, s2, s3 } = decrypt(xk, a0, a1, o32[pos], o32[pos + 1]);
                    (a0 = s0), (a1 = s1), (o32[pos] = s2), (o32[pos + 1] = s3);
                }
            }
            (o32[0] = a0), (o32[1] = a1);
        }
        xk.fill(0);
    },
};
const AESKW_IV = /* @__PURE__ */ new Uint8Array(8).fill(0xa6); // A6A6A6A6A6A6A6A6
/**
 * AES-KW (key-wrap). Injects static IV into plaintext, adds counter, encrypts 6 times.
 * Reduces block size from 16 to 8 bytes.
 * For padded version, use aeskwp.
 * [RFC 3394](https://datatracker.ietf.org/doc/rfc3394/),
 * [NIST.SP.800-38F](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-38F.pdf).
 */
const aeskw = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.wrapCipher)({ blockSize: 8 }, (kek) => ({
    encrypt(plaintext) {
        if (!plaintext.length || plaintext.length % 8 !== 0)
            throw new Error('invalid plaintext length');
        if (plaintext.length === 8)
            throw new Error('8-byte keys not allowed in AESKW, use AESKWP instead');
        const out = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(AESKW_IV, plaintext);
        AESW.encrypt(kek, out);
        return out;
    },
    decrypt(ciphertext) {
        // ciphertext must be at least 24 bytes and a multiple of 8 bytes
        // 24 because should have at least two block (1 iv + 2).
        // Replace with 16 to enable '8-byte keys'
        if (ciphertext.length % 8 !== 0 || ciphertext.length < 3 * 8)
            throw new Error('invalid ciphertext length');
        const out = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(ciphertext);
        AESW.decrypt(kek, out);
        if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.equalBytes)(out.subarray(0, 8), AESKW_IV))
            throw new Error('integrity check failed');
        out.subarray(0, 8).fill(0); // ciphertext.subarray(0, 8) === IV, but we clean it anyway
        return out.subarray(8);
    },
}));
/*
We don't support 8-byte keys. The rabbit hole:

- Wycheproof says: "NIST SP 800-38F does not define the wrapping of 8 byte keys.
  RFC 3394 Section 2  on the other hand specifies that 8 byte keys are wrapped
  by directly encrypting one block with AES."
    - https://github.com/C2SP/wycheproof/blob/master/doc/key_wrap.md
    - "RFC 3394 specifies in Section 2, that the input for the key wrap
      algorithm must be at least two blocks and otherwise the constant
      field and key are simply encrypted with ECB as a single block"
- What RFC 3394 actually says (in Section 2):
    - "Before being wrapped, the key data is parsed into n blocks of 64 bits.
      The only restriction the key wrap algorithm places on n is that n be
      at least two"
    - "For key data with length less than or equal to 64 bits, the constant
      field used in this specification and the key data form a single
      128-bit codebook input making this key wrap unnecessary."
- Which means "assert(n >= 2)" and "use something else for 8 byte keys"
- NIST SP800-38F actually prohibits 8-byte in "5.3.1 Mandatory Limits".
  It states that plaintext for KW should be "2 to 2^54 -1 semiblocks".
- So, where does "directly encrypt single block with AES" come from?
    - Not RFC 3394. Pseudocode of key wrap in 2.2 explicitly uses
      loop of 6 for any code path
    - There is a weird W3C spec:
      https://www.w3.org/TR/2002/REC-xmlenc-core-20021210/Overview.html#kw-aes128
    - This spec is outdated, as admitted by Wycheproof authors
    - There is RFC 5649 for padded key wrap, which is padding construction on
      top of AESKW. In '4.1.2' it says: "If the padded plaintext contains exactly
      eight octets, then prepend the AIV as defined in Section 3 above to P[1] and
      encrypt the resulting 128-bit block using AES in ECB mode [Modes] with key
      K (the KEK).  In this case, the output is two 64-bit blocks C[0] and C[1]:"
    - Browser subtle crypto is actually crashes on wrapping keys less than 16 bytes:
      `Error: error:1C8000E6:Provider routines::invalid input length] { opensslErrorStack: [ 'error:030000BD:digital envelope routines::update error' ]`

In the end, seems like a bug in Wycheproof.
The 8-byte check can be easily disabled inside of AES_W.
*/
const AESKWP_IV = 0xa65959a6; // single u32le value
/**
 * AES-KW, but with padding and allows random keys.
 * Second u32 of IV is used as counter for length.
 * [RFC 5649](https://www.rfc-editor.org/rfc/rfc5649)
 */
const aeskwp = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.wrapCipher)({ blockSize: 8 }, (kek) => ({
    encrypt(plaintext) {
        if (!plaintext.length)
            throw new Error('invalid plaintext length');
        const padded = Math.ceil(plaintext.length / 8) * 8;
        const out = new Uint8Array(8 + padded);
        out.set(plaintext, 8);
        const out32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(out);
        out32[0] = AESKWP_IV;
        out32[1] = byteSwap(plaintext.length);
        AESW.encrypt(kek, out);
        return out;
    },
    decrypt(ciphertext) {
        // 16 because should have at least one block
        if (ciphertext.length < 16)
            throw new Error('invalid ciphertext length');
        const out = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.copyBytes)(ciphertext);
        const o32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.u32)(out);
        AESW.decrypt(kek, out);
        const len = byteSwap(o32[1]) >>> 0;
        const padded = Math.ceil(len / 8) * 8;
        if (o32[0] !== AESKWP_IV || out.length - 8 !== padded)
            throw new Error('integrity check failed');
        for (let i = len; i < padded; i++)
            if (out[8 + i] !== 0)
                throw new Error('integrity check failed');
        out.subarray(0, 8).fill(0); // ciphertext.subarray(0, 8) === IV, but we clean it anyway
        return out.subarray(8, 8 + len);
    },
}));
/** Unsafe low-level internal methods. May change at any time. */
const unsafe = {
    expandKeyLE,
    expandKeyDecLE,
    encrypt,
    decrypt,
    encryptBlock,
    decryptBlock,
    ctrCounter,
    ctr32,
};
//# sourceMappingURL=aes.js.map

/***/ },

/***/ 943
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   chacha20: () => (/* binding */ chacha20),
/* harmony export */   xchacha20poly1305: () => (/* binding */ xchacha20poly1305)
/* harmony export */ });
/* unused harmony exports hchacha, chacha20orig, xchacha20, chacha8, chacha12, _poly1305_aead, chacha20poly1305 */
/* harmony import */ var _arx_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(869);
/* harmony import */ var _poly1305_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(935);
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(34);
/**
 * [ChaCha20](https://cr.yp.to/chacha.html) stream cipher, released
 * in 2008. Developed after Salsa20, ChaCha aims to increase diffusion per round.
 * It was standardized in [RFC 8439](https://datatracker.ietf.org/doc/html/rfc8439) and
 * is now used in TLS 1.3.
 *
 * [XChaCha20](https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-xchacha)
 * extended-nonce variant is also provided. Similar to XSalsa, it's safe to use with
 * randomly-generated nonces.
 *
 * Check out [PDF](http://cr.yp.to/chacha/chacha-20080128.pdf) and
 * [wiki](https://en.wikipedia.org/wiki/Salsa20).
 * @module
 */



/**
 * ChaCha core function.
 */
// prettier-ignore
function chachaCore(s, k, n, out, cnt, rounds = 20) {
    let y00 = s[0], y01 = s[1], y02 = s[2], y03 = s[3], // "expa"   "nd 3"  "2-by"  "te k"
    y04 = k[0], y05 = k[1], y06 = k[2], y07 = k[3], // Key      Key     Key     Key
    y08 = k[4], y09 = k[5], y10 = k[6], y11 = k[7], // Key      Key     Key     Key
    y12 = cnt, y13 = n[0], y14 = n[1], y15 = n[2]; // Counter  Counter	Nonce   Nonce
    // Save state to temporary variables
    let x00 = y00, x01 = y01, x02 = y02, x03 = y03, x04 = y04, x05 = y05, x06 = y06, x07 = y07, x08 = y08, x09 = y09, x10 = y10, x11 = y11, x12 = y12, x13 = y13, x14 = y14, x15 = y15;
    for (let r = 0; r < rounds; r += 2) {
        x00 = (x00 + x04) | 0;
        x12 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x12 ^ x00, 16);
        x08 = (x08 + x12) | 0;
        x04 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x04 ^ x08, 12);
        x00 = (x00 + x04) | 0;
        x12 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x12 ^ x00, 8);
        x08 = (x08 + x12) | 0;
        x04 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x04 ^ x08, 7);
        x01 = (x01 + x05) | 0;
        x13 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x13 ^ x01, 16);
        x09 = (x09 + x13) | 0;
        x05 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x05 ^ x09, 12);
        x01 = (x01 + x05) | 0;
        x13 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x13 ^ x01, 8);
        x09 = (x09 + x13) | 0;
        x05 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x05 ^ x09, 7);
        x02 = (x02 + x06) | 0;
        x14 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x14 ^ x02, 16);
        x10 = (x10 + x14) | 0;
        x06 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x06 ^ x10, 12);
        x02 = (x02 + x06) | 0;
        x14 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x14 ^ x02, 8);
        x10 = (x10 + x14) | 0;
        x06 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x06 ^ x10, 7);
        x03 = (x03 + x07) | 0;
        x15 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x15 ^ x03, 16);
        x11 = (x11 + x15) | 0;
        x07 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x07 ^ x11, 12);
        x03 = (x03 + x07) | 0;
        x15 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x15 ^ x03, 8);
        x11 = (x11 + x15) | 0;
        x07 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x07 ^ x11, 7);
        x00 = (x00 + x05) | 0;
        x15 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x15 ^ x00, 16);
        x10 = (x10 + x15) | 0;
        x05 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x05 ^ x10, 12);
        x00 = (x00 + x05) | 0;
        x15 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x15 ^ x00, 8);
        x10 = (x10 + x15) | 0;
        x05 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x05 ^ x10, 7);
        x01 = (x01 + x06) | 0;
        x12 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x12 ^ x01, 16);
        x11 = (x11 + x12) | 0;
        x06 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x06 ^ x11, 12);
        x01 = (x01 + x06) | 0;
        x12 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x12 ^ x01, 8);
        x11 = (x11 + x12) | 0;
        x06 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x06 ^ x11, 7);
        x02 = (x02 + x07) | 0;
        x13 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x13 ^ x02, 16);
        x08 = (x08 + x13) | 0;
        x07 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x07 ^ x08, 12);
        x02 = (x02 + x07) | 0;
        x13 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x13 ^ x02, 8);
        x08 = (x08 + x13) | 0;
        x07 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x07 ^ x08, 7);
        x03 = (x03 + x04) | 0;
        x14 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x14 ^ x03, 16);
        x09 = (x09 + x14) | 0;
        x04 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x04 ^ x09, 12);
        x03 = (x03 + x04) | 0;
        x14 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x14 ^ x03, 8);
        x09 = (x09 + x14) | 0;
        x04 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x04 ^ x09, 7);
    }
    // Write output
    let oi = 0;
    out[oi++] = (y00 + x00) | 0;
    out[oi++] = (y01 + x01) | 0;
    out[oi++] = (y02 + x02) | 0;
    out[oi++] = (y03 + x03) | 0;
    out[oi++] = (y04 + x04) | 0;
    out[oi++] = (y05 + x05) | 0;
    out[oi++] = (y06 + x06) | 0;
    out[oi++] = (y07 + x07) | 0;
    out[oi++] = (y08 + x08) | 0;
    out[oi++] = (y09 + x09) | 0;
    out[oi++] = (y10 + x10) | 0;
    out[oi++] = (y11 + x11) | 0;
    out[oi++] = (y12 + x12) | 0;
    out[oi++] = (y13 + x13) | 0;
    out[oi++] = (y14 + x14) | 0;
    out[oi++] = (y15 + x15) | 0;
}
/**
 * hchacha helper method, used primarily in xchacha, to hash
 * key and nonce into key' and nonce'.
 * Same as chachaCore, but there doesn't seem to be a way to move the block
 * out without 25% performance hit.
 */
// prettier-ignore
function hchacha(s, k, i, o32) {
    let x00 = s[0], x01 = s[1], x02 = s[2], x03 = s[3], x04 = k[0], x05 = k[1], x06 = k[2], x07 = k[3], x08 = k[4], x09 = k[5], x10 = k[6], x11 = k[7], x12 = i[0], x13 = i[1], x14 = i[2], x15 = i[3];
    for (let r = 0; r < 20; r += 2) {
        x00 = (x00 + x04) | 0;
        x12 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x12 ^ x00, 16);
        x08 = (x08 + x12) | 0;
        x04 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x04 ^ x08, 12);
        x00 = (x00 + x04) | 0;
        x12 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x12 ^ x00, 8);
        x08 = (x08 + x12) | 0;
        x04 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x04 ^ x08, 7);
        x01 = (x01 + x05) | 0;
        x13 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x13 ^ x01, 16);
        x09 = (x09 + x13) | 0;
        x05 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x05 ^ x09, 12);
        x01 = (x01 + x05) | 0;
        x13 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x13 ^ x01, 8);
        x09 = (x09 + x13) | 0;
        x05 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x05 ^ x09, 7);
        x02 = (x02 + x06) | 0;
        x14 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x14 ^ x02, 16);
        x10 = (x10 + x14) | 0;
        x06 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x06 ^ x10, 12);
        x02 = (x02 + x06) | 0;
        x14 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x14 ^ x02, 8);
        x10 = (x10 + x14) | 0;
        x06 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x06 ^ x10, 7);
        x03 = (x03 + x07) | 0;
        x15 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x15 ^ x03, 16);
        x11 = (x11 + x15) | 0;
        x07 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x07 ^ x11, 12);
        x03 = (x03 + x07) | 0;
        x15 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x15 ^ x03, 8);
        x11 = (x11 + x15) | 0;
        x07 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x07 ^ x11, 7);
        x00 = (x00 + x05) | 0;
        x15 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x15 ^ x00, 16);
        x10 = (x10 + x15) | 0;
        x05 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x05 ^ x10, 12);
        x00 = (x00 + x05) | 0;
        x15 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x15 ^ x00, 8);
        x10 = (x10 + x15) | 0;
        x05 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x05 ^ x10, 7);
        x01 = (x01 + x06) | 0;
        x12 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x12 ^ x01, 16);
        x11 = (x11 + x12) | 0;
        x06 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x06 ^ x11, 12);
        x01 = (x01 + x06) | 0;
        x12 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x12 ^ x01, 8);
        x11 = (x11 + x12) | 0;
        x06 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x06 ^ x11, 7);
        x02 = (x02 + x07) | 0;
        x13 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x13 ^ x02, 16);
        x08 = (x08 + x13) | 0;
        x07 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x07 ^ x08, 12);
        x02 = (x02 + x07) | 0;
        x13 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x13 ^ x02, 8);
        x08 = (x08 + x13) | 0;
        x07 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x07 ^ x08, 7);
        x03 = (x03 + x04) | 0;
        x14 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x14 ^ x03, 16);
        x09 = (x09 + x14) | 0;
        x04 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x04 ^ x09, 12);
        x03 = (x03 + x04) | 0;
        x14 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x14 ^ x03, 8);
        x09 = (x09 + x14) | 0;
        x04 = (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.rotl)(x04 ^ x09, 7);
    }
    let oi = 0;
    o32[oi++] = x00;
    o32[oi++] = x01;
    o32[oi++] = x02;
    o32[oi++] = x03;
    o32[oi++] = x12;
    o32[oi++] = x13;
    o32[oi++] = x14;
    o32[oi++] = x15;
}
/**
 * Original, non-RFC chacha20 from DJB. 8-byte nonce, 8-byte counter.
 */
const chacha20orig = /* @__PURE__ */ (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.createCipher)(chachaCore, {
    counterRight: false,
    counterLength: 8,
    allowShortKeys: true,
});
/**
 * ChaCha stream cipher. Conforms to RFC 8439 (IETF, TLS). 12-byte nonce, 4-byte counter.
 * With 12-byte nonce, it's not safe to use fill it with random (CSPRNG), due to collision chance.
 */
const chacha20 = /* @__PURE__ */ (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.createCipher)(chachaCore, {
    counterRight: false,
    counterLength: 4,
    allowShortKeys: false,
});
/**
 * XChaCha eXtended-nonce ChaCha. 24-byte nonce.
 * With 24-byte nonce, it's safe to use fill it with random (CSPRNG).
 * https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-xchacha
 */
const xchacha20 = /* @__PURE__ */ (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.createCipher)(chachaCore, {
    counterRight: false,
    counterLength: 8,
    extendNonceFn: hchacha,
    allowShortKeys: false,
});
/**
 * Reduced 8-round chacha, described in original paper.
 */
const chacha8 = /* @__PURE__ */ (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.createCipher)(chachaCore, {
    counterRight: false,
    counterLength: 4,
    rounds: 8,
});
/**
 * Reduced 12-round chacha, described in original paper.
 */
const chacha12 = /* @__PURE__ */ (0,_arx_js__WEBPACK_IMPORTED_MODULE_0__.createCipher)(chachaCore, {
    counterRight: false,
    counterLength: 4,
    rounds: 12,
});
const ZEROS16 = /* @__PURE__ */ new Uint8Array(16);
// Pad to digest size with zeros
const updatePadded = (h, msg) => {
    h.update(msg);
    const left = msg.length % 16;
    if (left)
        h.update(ZEROS16.subarray(left));
};
const ZEROS32 = /* @__PURE__ */ new Uint8Array(32);
function computeTag(fn, key, nonce, data, AAD) {
    const authKey = fn(key, nonce, ZEROS32);
    const h = _poly1305_js__WEBPACK_IMPORTED_MODULE_1__.poly1305.create(authKey);
    if (AAD)
        updatePadded(h, AAD);
    updatePadded(h, data);
    const num = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.u64Lengths)(data.length, AAD ? AAD.length : 0, true);
    h.update(num);
    const res = h.digest();
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.clean)(authKey, num);
    return res;
}
/**
 * AEAD algorithm from RFC 8439.
 * Salsa20 and chacha (RFC 8439) use poly1305 differently.
 * We could have composed them similar to:
 * https://github.com/paulmillr/scure-base/blob/b266c73dde977b1dd7ef40ef7a23cc15aab526b3/index.ts#L250
 * But it's hard because of authKey:
 * In salsa20, authKey changes position in salsa stream.
 * In chacha, authKey can't be computed inside computeTag, it modifies the counter.
 */
const _poly1305_aead = (xorStream) => (key, nonce, AAD) => {
    const tagLength = 16;
    return {
        encrypt(plaintext, output) {
            const plength = plaintext.length;
            output = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.getOutput)(plength + tagLength, output, false);
            output.set(plaintext);
            const oPlain = output.subarray(0, -tagLength);
            xorStream(key, nonce, oPlain, oPlain, 1);
            const tag = computeTag(xorStream, key, nonce, oPlain, AAD);
            output.set(tag, plength); // append tag
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.clean)(tag);
            return output;
        },
        decrypt(ciphertext, output) {
            output = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.getOutput)(ciphertext.length - tagLength, output, false);
            const data = ciphertext.subarray(0, -tagLength);
            const passedTag = ciphertext.subarray(-tagLength);
            const tag = computeTag(xorStream, key, nonce, data, AAD);
            if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.equalBytes)(passedTag, tag))
                throw new Error('invalid tag');
            output.set(ciphertext.subarray(0, -tagLength));
            xorStream(key, nonce, output, output, 1); // start stream with i=1
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.clean)(tag);
            return output;
        },
    };
};
/**
 * ChaCha20-Poly1305 from RFC 8439.
 *
 * Unsafe to use random nonces under the same key, due to collision chance.
 * Prefer XChaCha instead.
 */
const chacha20poly1305 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.wrapCipher)({ blockSize: 64, nonceLength: 12, tagLength: 16 }, _poly1305_aead(chacha20));
/**
 * XChaCha20-Poly1305 extended-nonce chacha.
 *
 * Can be safely used with random nonces (CSPRNG).
 * See [IRTF draft](https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-xchacha).
 */
const xchacha20poly1305 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.wrapCipher)({ blockSize: 64, nonceLength: 24, tagLength: 16 }, _poly1305_aead(xchacha20));
//# sourceMappingURL=chacha.js.map

/***/ },

/***/ 34
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   abool: () => (/* binding */ abool),
/* harmony export */   abytes: () => (/* binding */ abytes),
/* harmony export */   aexists: () => (/* binding */ aexists),
/* harmony export */   anumber: () => (/* binding */ anumber),
/* harmony export */   aoutput: () => (/* binding */ aoutput),
/* harmony export */   checkOpts: () => (/* binding */ checkOpts),
/* harmony export */   clean: () => (/* binding */ clean),
/* harmony export */   complexOverlapBytes: () => (/* binding */ complexOverlapBytes),
/* harmony export */   concatBytes: () => (/* binding */ concatBytes),
/* harmony export */   copyBytes: () => (/* binding */ copyBytes),
/* harmony export */   createView: () => (/* binding */ createView),
/* harmony export */   equalBytes: () => (/* binding */ equalBytes),
/* harmony export */   getOutput: () => (/* binding */ getOutput),
/* harmony export */   isAligned32: () => (/* binding */ isAligned32),
/* harmony export */   overlapBytes: () => (/* binding */ overlapBytes),
/* harmony export */   setBigUint64: () => (/* binding */ setBigUint64),
/* harmony export */   toBytes: () => (/* binding */ toBytes),
/* harmony export */   u32: () => (/* binding */ u32),
/* harmony export */   u64Lengths: () => (/* binding */ u64Lengths),
/* harmony export */   u8: () => (/* binding */ u8),
/* harmony export */   wrapCipher: () => (/* binding */ wrapCipher)
/* harmony export */ });
/* unused harmony exports isBytes, ahash, isLE, bytesToHex, hexToBytes, hexToNumber, bytesToNumberBE, numberToBytesBE, nextTick, utf8ToBytes, bytesToUtf8, Hash */
/**
 * Utilities for hex, bytes, CSPRNG.
 * @module
 */
/*! noble-ciphers - MIT License (c) 2023 Paul Miller (paulmillr.com) */
/** Checks if something is Uint8Array. Be careful: nodejs Buffer will return true. */
function isBytes(a) {
    return a instanceof Uint8Array || (ArrayBuffer.isView(a) && a.constructor.name === 'Uint8Array');
}
/** Asserts something is boolean. */
function abool(b) {
    if (typeof b !== 'boolean')
        throw new Error(`boolean expected, not ${b}`);
}
/** Asserts something is positive integer. */
function anumber(n) {
    if (!Number.isSafeInteger(n) || n < 0)
        throw new Error('positive integer expected, got ' + n);
}
/** Asserts something is Uint8Array. */
function abytes(b, ...lengths) {
    if (!isBytes(b))
        throw new Error('Uint8Array expected');
    if (lengths.length > 0 && !lengths.includes(b.length))
        throw new Error('Uint8Array expected of length ' + lengths + ', got length=' + b.length);
}
/**
 * Asserts something is hash
 * TODO: remove
 * @deprecated
 */
function ahash(h) {
    if (typeof h !== 'function' || typeof h.create !== 'function')
        throw new Error('Hash should be wrapped by utils.createHasher');
    anumber(h.outputLen);
    anumber(h.blockLen);
}
/** Asserts a hash instance has not been destroyed / finished */
function aexists(instance, checkFinished = true) {
    if (instance.destroyed)
        throw new Error('Hash instance has been destroyed');
    if (checkFinished && instance.finished)
        throw new Error('Hash#digest() has already been called');
}
/** Asserts output is properly-sized byte array */
function aoutput(out, instance) {
    abytes(out);
    const min = instance.outputLen;
    if (out.length < min) {
        throw new Error('digestInto() expects output buffer of length at least ' + min);
    }
}
/** Cast u8 / u16 / u32 to u8. */
function u8(arr) {
    return new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength);
}
/** Cast u8 / u16 / u32 to u32. */
function u32(arr) {
    return new Uint32Array(arr.buffer, arr.byteOffset, Math.floor(arr.byteLength / 4));
}
/** Zeroize a byte array. Warning: JS provides no guarantees. */
function clean(...arrays) {
    for (let i = 0; i < arrays.length; i++) {
        arrays[i].fill(0);
    }
}
/** Create DataView of an array for easy byte-level manipulation. */
function createView(arr) {
    return new DataView(arr.buffer, arr.byteOffset, arr.byteLength);
}
/** Is current platform little-endian? Most are. Big-Endian platform: IBM */
const isLE = /* @__PURE__ */ (() => new Uint8Array(new Uint32Array([0x11223344]).buffer)[0] === 0x44)();
// Built-in hex conversion https://caniuse.com/mdn-javascript_builtins_uint8array_fromhex
const hasHexBuiltin = /* @__PURE__ */ (/* unused pure expression or super */ null && ((() => 
// @ts-ignore
typeof Uint8Array.from([]).toHex === 'function' && typeof Uint8Array.fromHex === 'function')()));
// Array where index 0xf0 (240) is mapped to string 'f0'
const hexes = /* @__PURE__ */ Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, '0'));
/**
 * Convert byte array to hex string. Uses built-in function, when available.
 * @example bytesToHex(Uint8Array.from([0xca, 0xfe, 0x01, 0x23])) // 'cafe0123'
 */
function bytesToHex(bytes) {
    abytes(bytes);
    // @ts-ignore
    if (hasHexBuiltin)
        return bytes.toHex();
    // pre-caching improves the speed 6x
    let hex = '';
    for (let i = 0; i < bytes.length; i++) {
        hex += hexes[bytes[i]];
    }
    return hex;
}
// We use optimized technique to convert hex string to byte array
const asciis = { _0: 48, _9: 57, A: 65, F: 70, a: 97, f: 102 };
function asciiToBase16(ch) {
    if (ch >= asciis._0 && ch <= asciis._9)
        return ch - asciis._0; // '2' => 50-48
    if (ch >= asciis.A && ch <= asciis.F)
        return ch - (asciis.A - 10); // 'B' => 66-(65-10)
    if (ch >= asciis.a && ch <= asciis.f)
        return ch - (asciis.a - 10); // 'b' => 98-(97-10)
    return;
}
/**
 * Convert hex string to byte array. Uses built-in function, when available.
 * @example hexToBytes('cafe0123') // Uint8Array.from([0xca, 0xfe, 0x01, 0x23])
 */
function hexToBytes(hex) {
    if (typeof hex !== 'string')
        throw new Error('hex string expected, got ' + typeof hex);
    // @ts-ignore
    if (hasHexBuiltin)
        return Uint8Array.fromHex(hex);
    const hl = hex.length;
    const al = hl / 2;
    if (hl % 2)
        throw new Error('hex string expected, got unpadded hex of length ' + hl);
    const array = new Uint8Array(al);
    for (let ai = 0, hi = 0; ai < al; ai++, hi += 2) {
        const n1 = asciiToBase16(hex.charCodeAt(hi));
        const n2 = asciiToBase16(hex.charCodeAt(hi + 1));
        if (n1 === undefined || n2 === undefined) {
            const char = hex[hi] + hex[hi + 1];
            throw new Error('hex string expected, got non-hex character "' + char + '" at index ' + hi);
        }
        array[ai] = n1 * 16 + n2; // multiply first octet, e.g. 'a3' => 10*16+3 => 160 + 3 => 163
    }
    return array;
}
// Used in micro
function hexToNumber(hex) {
    if (typeof hex !== 'string')
        throw new Error('hex string expected, got ' + typeof hex);
    return BigInt(hex === '' ? '0' : '0x' + hex); // Big Endian
}
// Used in ff1
// BE: Big Endian, LE: Little Endian
function bytesToNumberBE(bytes) {
    return hexToNumber(bytesToHex(bytes));
}
// Used in micro, ff1
function numberToBytesBE(n, len) {
    return hexToBytes(n.toString(16).padStart(len * 2, '0'));
}
// TODO: remove
// There is no setImmediate in browser and setTimeout is slow.
// call of async fn will return Promise, which will be fullfiled only on
// next scheduler queue processing step and this is exactly what we need.
const nextTick = async () => { };
/**
 * Converts string to bytes using UTF8 encoding.
 * @example utf8ToBytes('abc') // new Uint8Array([97, 98, 99])
 */
function utf8ToBytes(str) {
    if (typeof str !== 'string')
        throw new Error('string expected');
    return new Uint8Array(new TextEncoder().encode(str)); // https://bugzil.la/1681809
}
/**
 * Converts bytes to string using UTF8 encoding.
 * @example bytesToUtf8(new Uint8Array([97, 98, 99])) // 'abc'
 */
function bytesToUtf8(bytes) {
    return new TextDecoder().decode(bytes);
}
/**
 * Normalizes (non-hex) string or Uint8Array to Uint8Array.
 * Warning: when Uint8Array is passed, it would NOT get copied.
 * Keep in mind for future mutable operations.
 */
function toBytes(data) {
    if (typeof data === 'string')
        data = utf8ToBytes(data);
    else if (isBytes(data))
        data = copyBytes(data);
    else
        throw new Error('Uint8Array expected, got ' + typeof data);
    return data;
}
/**
 * Checks if two U8A use same underlying buffer and overlaps.
 * This is invalid and can corrupt data.
 */
function overlapBytes(a, b) {
    return (a.buffer === b.buffer && // best we can do, may fail with an obscure Proxy
        a.byteOffset < b.byteOffset + b.byteLength && // a starts before b end
        b.byteOffset < a.byteOffset + a.byteLength // b starts before a end
    );
}
/**
 * If input and output overlap and input starts before output, we will overwrite end of input before
 * we start processing it, so this is not supported for most ciphers (except chacha/salse, which designed with this)
 */
function complexOverlapBytes(input, output) {
    // This is very cursed. It works somehow, but I'm completely unsure,
    // reasoning about overlapping aligned windows is very hard.
    if (overlapBytes(input, output) && input.byteOffset < output.byteOffset)
        throw new Error('complex overlap of input and output is not supported');
}
/**
 * Copies several Uint8Arrays into one.
 */
function concatBytes(...arrays) {
    let sum = 0;
    for (let i = 0; i < arrays.length; i++) {
        const a = arrays[i];
        abytes(a);
        sum += a.length;
    }
    const res = new Uint8Array(sum);
    for (let i = 0, pad = 0; i < arrays.length; i++) {
        const a = arrays[i];
        res.set(a, pad);
        pad += a.length;
    }
    return res;
}
function checkOpts(defaults, opts) {
    if (opts == null || typeof opts !== 'object')
        throw new Error('options must be defined');
    const merged = Object.assign(defaults, opts);
    return merged;
}
/** Compares 2 uint8array-s in kinda constant time. */
function equalBytes(a, b) {
    if (a.length !== b.length)
        return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++)
        diff |= a[i] ^ b[i];
    return diff === 0;
}
// TODO: remove
/** For runtime check if class implements interface. */
class Hash {
}
/**
 * Wraps a cipher: validates args, ensures encrypt() can only be called once.
 * @__NO_SIDE_EFFECTS__
 */
const wrapCipher = (params, constructor) => {
    function wrappedCipher(key, ...args) {
        // Validate key
        abytes(key);
        // Big-Endian hardware is rare. Just in case someone still decides to run ciphers:
        if (!isLE)
            throw new Error('Non little-endian hardware is not yet supported');
        // Validate nonce if nonceLength is present
        if (params.nonceLength !== undefined) {
            const nonce = args[0];
            if (!nonce)
                throw new Error('nonce / iv required');
            if (params.varSizeNonce)
                abytes(nonce);
            else
                abytes(nonce, params.nonceLength);
        }
        // Validate AAD if tagLength present
        const tagl = params.tagLength;
        if (tagl && args[1] !== undefined) {
            abytes(args[1]);
        }
        const cipher = constructor(key, ...args);
        const checkOutput = (fnLength, output) => {
            if (output !== undefined) {
                if (fnLength !== 2)
                    throw new Error('cipher output not supported');
                abytes(output);
            }
        };
        // Create wrapped cipher with validation and single-use encryption
        let called = false;
        const wrCipher = {
            encrypt(data, output) {
                if (called)
                    throw new Error('cannot encrypt() twice with same key + nonce');
                called = true;
                abytes(data);
                checkOutput(cipher.encrypt.length, output);
                return cipher.encrypt(data, output);
            },
            decrypt(data, output) {
                abytes(data);
                if (tagl && data.length < tagl)
                    throw new Error('invalid ciphertext length: smaller than tagLength=' + tagl);
                checkOutput(cipher.decrypt.length, output);
                return cipher.decrypt(data, output);
            },
        };
        return wrCipher;
    }
    Object.assign(wrappedCipher, params);
    return wrappedCipher;
};
/**
 * By default, returns u8a of length.
 * When out is available, it checks it for validity and uses it.
 */
function getOutput(expectedLength, out, onlyAligned = true) {
    if (out === undefined)
        return new Uint8Array(expectedLength);
    if (out.length !== expectedLength)
        throw new Error('invalid output length, expected ' + expectedLength + ', got: ' + out.length);
    if (onlyAligned && !isAligned32(out))
        throw new Error('invalid output, must be aligned');
    return out;
}
/** Polyfill for Safari 14. */
function setBigUint64(view, byteOffset, value, isLE) {
    if (typeof view.setBigUint64 === 'function')
        return view.setBigUint64(byteOffset, value, isLE);
    const _32n = BigInt(32);
    const _u32_max = BigInt(0xffffffff);
    const wh = Number((value >> _32n) & _u32_max);
    const wl = Number(value & _u32_max);
    const h = isLE ? 4 : 0;
    const l = isLE ? 0 : 4;
    view.setUint32(byteOffset + h, wh, isLE);
    view.setUint32(byteOffset + l, wl, isLE);
}
function u64Lengths(dataLength, aadLength, isLE) {
    abool(isLE);
    const num = new Uint8Array(16);
    const view = createView(num);
    setBigUint64(view, 0, BigInt(aadLength), isLE);
    setBigUint64(view, 8, BigInt(dataLength), isLE);
    return num;
}
// Is byte array aligned to 4 byte offset (u32)?
function isAligned32(bytes) {
    return bytes.byteOffset % 4 === 0;
}
// copy bytes to new u8a (aligned). Because Buffer.slice is broken.
function copyBytes(bytes) {
    return Uint8Array.from(bytes);
}
//# sourceMappingURL=utils.js.map

/***/ },

/***/ 333
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   createCurve: () => (/* binding */ createCurve)
/* harmony export */ });
/* unused harmony export getHash */
/* harmony import */ var _abstract_weierstrass_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(316);
/**
 * Utilities for short weierstrass curves, combined with noble-hashes.
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */

/** connects noble-curves to noble-hashes */
function getHash(hash) {
    return { hash };
}
/** @deprecated use new `weierstrass()` and `ecdsa()` methods */
function createCurve(curveDef, defHash) {
    const create = (hash) => (0,_abstract_weierstrass_js__WEBPACK_IMPORTED_MODULE_0__.weierstrass)({ ...curveDef, hash: hash });
    return { ...create(defHash), create };
}
//# sourceMappingURL=_shortw_utils.js.map

/***/ },

/***/ 615
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   _createCurveFields: () => (/* binding */ _createCurveFields),
/* harmony export */   mulEndoUnsafe: () => (/* binding */ mulEndoUnsafe),
/* harmony export */   negateCt: () => (/* binding */ negateCt),
/* harmony export */   normalizeZ: () => (/* binding */ normalizeZ),
/* harmony export */   pippenger: () => (/* binding */ pippenger),
/* harmony export */   wNAF: () => (/* binding */ wNAF)
/* harmony export */ });
/* unused harmony exports precomputeMSMUnsafe, validateBasic */
/* unused harmony import specifier */ var bitMask;
/* unused harmony import specifier */ var validateObject;
/* unused harmony import specifier */ var validateField;
/* unused harmony import specifier */ var nLength;
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(848);
/* harmony import */ var _modular_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(30);
/**
 * Methods for elliptic curve multiplication by scalars.
 * Contains wNAF, pippenger.
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */


const _0n = BigInt(0);
const _1n = BigInt(1);
function negateCt(condition, item) {
    const neg = item.negate();
    return condition ? neg : item;
}
/**
 * Takes a bunch of Projective Points but executes only one
 * inversion on all of them. Inversion is very slow operation,
 * so this improves performance massively.
 * Optimization: converts a list of projective points to a list of identical points with Z=1.
 */
function normalizeZ(c, points) {
    const invertedZs = (0,_modular_js__WEBPACK_IMPORTED_MODULE_1__.FpInvertBatch)(c.Fp, points.map((p) => p.Z));
    return points.map((p, i) => c.fromAffine(p.toAffine(invertedZs[i])));
}
function validateW(W, bits) {
    if (!Number.isSafeInteger(W) || W <= 0 || W > bits)
        throw new Error('invalid window size, expected [1..' + bits + '], got W=' + W);
}
function calcWOpts(W, scalarBits) {
    validateW(W, scalarBits);
    const windows = Math.ceil(scalarBits / W) + 1; // W=8 33. Not 32, because we skip zero
    const windowSize = 2 ** (W - 1); // W=8 128. Not 256, because we skip zero
    const maxNumber = 2 ** W; // W=8 256
    const mask = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitMask)(W); // W=8 255 == mask 0b11111111
    const shiftBy = BigInt(W); // W=8 8
    return { windows, windowSize, mask, maxNumber, shiftBy };
}
function calcOffsets(n, window, wOpts) {
    const { windowSize, mask, maxNumber, shiftBy } = wOpts;
    let wbits = Number(n & mask); // extract W bits.
    let nextN = n >> shiftBy; // shift number by W bits.
    // What actually happens here:
    // const highestBit = Number(mask ^ (mask >> 1n));
    // let wbits2 = wbits - 1; // skip zero
    // if (wbits2 & highestBit) { wbits2 ^= Number(mask); // (~);
    // split if bits > max: +224 => 256-32
    if (wbits > windowSize) {
        // we skip zero, which means instead of `>= size-1`, we do `> size`
        wbits -= maxNumber; // -32, can be maxNumber - wbits, but then we need to set isNeg here.
        nextN += _1n; // +256 (carry)
    }
    const offsetStart = window * windowSize;
    const offset = offsetStart + Math.abs(wbits) - 1; // -1 because we skip zero
    const isZero = wbits === 0; // is current window slice a 0?
    const isNeg = wbits < 0; // is current window slice negative?
    const isNegF = window % 2 !== 0; // fake random statement for noise
    const offsetF = offsetStart; // fake offset for noise
    return { nextN, offset, isZero, isNeg, isNegF, offsetF };
}
function validateMSMPoints(points, c) {
    if (!Array.isArray(points))
        throw new Error('array expected');
    points.forEach((p, i) => {
        if (!(p instanceof c))
            throw new Error('invalid point at index ' + i);
    });
}
function validateMSMScalars(scalars, field) {
    if (!Array.isArray(scalars))
        throw new Error('array of scalars expected');
    scalars.forEach((s, i) => {
        if (!field.isValid(s))
            throw new Error('invalid scalar at index ' + i);
    });
}
// Since points in different groups cannot be equal (different object constructor),
// we can have single place to store precomputes.
// Allows to make points frozen / immutable.
const pointPrecomputes = new WeakMap();
const pointWindowSizes = new WeakMap();
function getW(P) {
    // To disable precomputes:
    // return 1;
    return pointWindowSizes.get(P) || 1;
}
function assert0(n) {
    if (n !== _0n)
        throw new Error('invalid wNAF');
}
/**
 * Elliptic curve multiplication of Point by scalar. Fragile.
 * Table generation takes **30MB of ram and 10ms on high-end CPU**,
 * but may take much longer on slow devices. Actual generation will happen on
 * first call of `multiply()`. By default, `BASE` point is precomputed.
 *
 * Scalars should always be less than curve order: this should be checked inside of a curve itself.
 * Creates precomputation tables for fast multiplication:
 * - private scalar is split by fixed size windows of W bits
 * - every window point is collected from window's table & added to accumulator
 * - since windows are different, same point inside tables won't be accessed more than once per calc
 * - each multiplication is 'Math.ceil(CURVE_ORDER / 𝑊) + 1' point additions (fixed for any scalar)
 * - +1 window is neccessary for wNAF
 * - wNAF reduces table size: 2x less memory + 2x faster generation, but 10% slower multiplication
 *
 * @todo Research returning 2d JS array of windows, instead of a single window.
 * This would allow windows to be in different memory locations
 */
class wNAF {
    // Parametrized with a given Point class (not individual point)
    constructor(Point, bits) {
        this.BASE = Point.BASE;
        this.ZERO = Point.ZERO;
        this.Fn = Point.Fn;
        this.bits = bits;
    }
    // non-const time multiplication ladder
    _unsafeLadder(elm, n, p = this.ZERO) {
        let d = elm;
        while (n > _0n) {
            if (n & _1n)
                p = p.add(d);
            d = d.double();
            n >>= _1n;
        }
        return p;
    }
    /**
     * Creates a wNAF precomputation window. Used for caching.
     * Default window size is set by `utils.precompute()` and is equal to 8.
     * Number of precomputed points depends on the curve size:
     * 2^(𝑊−1) * (Math.ceil(𝑛 / 𝑊) + 1), where:
     * - 𝑊 is the window size
     * - 𝑛 is the bitlength of the curve order.
     * For a 256-bit curve and window size 8, the number of precomputed points is 128 * 33 = 4224.
     * @param point Point instance
     * @param W window size
     * @returns precomputed point tables flattened to a single array
     */
    precomputeWindow(point, W) {
        const { windows, windowSize } = calcWOpts(W, this.bits);
        const points = [];
        let p = point;
        let base = p;
        for (let window = 0; window < windows; window++) {
            base = p;
            points.push(base);
            // i=1, bc we skip 0
            for (let i = 1; i < windowSize; i++) {
                base = base.add(p);
                points.push(base);
            }
            p = base.double();
        }
        return points;
    }
    /**
     * Implements ec multiplication using precomputed tables and w-ary non-adjacent form.
     * More compact implementation:
     * https://github.com/paulmillr/noble-secp256k1/blob/47cb1669b6e506ad66b35fe7d76132ae97465da2/index.ts#L502-L541
     * @returns real and fake (for const-time) points
     */
    wNAF(W, precomputes, n) {
        // Scalar should be smaller than field order
        if (!this.Fn.isValid(n))
            throw new Error('invalid scalar');
        // Accumulators
        let p = this.ZERO;
        let f = this.BASE;
        // This code was first written with assumption that 'f' and 'p' will never be infinity point:
        // since each addition is multiplied by 2 ** W, it cannot cancel each other. However,
        // there is negate now: it is possible that negated element from low value
        // would be the same as high element, which will create carry into next window.
        // It's not obvious how this can fail, but still worth investigating later.
        const wo = calcWOpts(W, this.bits);
        for (let window = 0; window < wo.windows; window++) {
            // (n === _0n) is handled and not early-exited. isEven and offsetF are used for noise
            const { nextN, offset, isZero, isNeg, isNegF, offsetF } = calcOffsets(n, window, wo);
            n = nextN;
            if (isZero) {
                // bits are 0: add garbage to fake point
                // Important part for const-time getPublicKey: add random "noise" point to f.
                f = f.add(negateCt(isNegF, precomputes[offsetF]));
            }
            else {
                // bits are 1: add to result point
                p = p.add(negateCt(isNeg, precomputes[offset]));
            }
        }
        assert0(n);
        // Return both real and fake points: JIT won't eliminate f.
        // At this point there is a way to F be infinity-point even if p is not,
        // which makes it less const-time: around 1 bigint multiply.
        return { p, f };
    }
    /**
     * Implements ec unsafe (non const-time) multiplication using precomputed tables and w-ary non-adjacent form.
     * @param acc accumulator point to add result of multiplication
     * @returns point
     */
    wNAFUnsafe(W, precomputes, n, acc = this.ZERO) {
        const wo = calcWOpts(W, this.bits);
        for (let window = 0; window < wo.windows; window++) {
            if (n === _0n)
                break; // Early-exit, skip 0 value
            const { nextN, offset, isZero, isNeg } = calcOffsets(n, window, wo);
            n = nextN;
            if (isZero) {
                // Window bits are 0: skip processing.
                // Move to next window.
                continue;
            }
            else {
                const item = precomputes[offset];
                acc = acc.add(isNeg ? item.negate() : item); // Re-using acc allows to save adds in MSM
            }
        }
        assert0(n);
        return acc;
    }
    getPrecomputes(W, point, transform) {
        // Calculate precomputes on a first run, reuse them after
        let comp = pointPrecomputes.get(point);
        if (!comp) {
            comp = this.precomputeWindow(point, W);
            if (W !== 1) {
                // Doing transform outside of if brings 15% perf hit
                if (typeof transform === 'function')
                    comp = transform(comp);
                pointPrecomputes.set(point, comp);
            }
        }
        return comp;
    }
    cached(point, scalar, transform) {
        const W = getW(point);
        return this.wNAF(W, this.getPrecomputes(W, point, transform), scalar);
    }
    unsafe(point, scalar, transform, prev) {
        const W = getW(point);
        if (W === 1)
            return this._unsafeLadder(point, scalar, prev); // For W=1 ladder is ~x2 faster
        return this.wNAFUnsafe(W, this.getPrecomputes(W, point, transform), scalar, prev);
    }
    // We calculate precomputes for elliptic curve point multiplication
    // using windowed method. This specifies window size and
    // stores precomputed values. Usually only base point would be precomputed.
    createCache(P, W) {
        validateW(W, this.bits);
        pointWindowSizes.set(P, W);
        pointPrecomputes.delete(P);
    }
    hasCache(elm) {
        return getW(elm) !== 1;
    }
}
/**
 * Endomorphism-specific multiplication for Koblitz curves.
 * Cost: 128 dbl, 0-256 adds.
 */
function mulEndoUnsafe(Point, point, k1, k2) {
    let acc = point;
    let p1 = Point.ZERO;
    let p2 = Point.ZERO;
    while (k1 > _0n || k2 > _0n) {
        if (k1 & _1n)
            p1 = p1.add(acc);
        if (k2 & _1n)
            p2 = p2.add(acc);
        acc = acc.double();
        k1 >>= _1n;
        k2 >>= _1n;
    }
    return { p1, p2 };
}
/**
 * Pippenger algorithm for multi-scalar multiplication (MSM, Pa + Qb + Rc + ...).
 * 30x faster vs naive addition on L=4096, 10x faster than precomputes.
 * For N=254bit, L=1, it does: 1024 ADD + 254 DBL. For L=5: 1536 ADD + 254 DBL.
 * Algorithmically constant-time (for same L), even when 1 point + scalar, or when scalar = 0.
 * @param c Curve Point constructor
 * @param fieldN field over CURVE.N - important that it's not over CURVE.P
 * @param points array of L curve points
 * @param scalars array of L scalars (aka secret keys / bigints)
 */
function pippenger(c, fieldN, points, scalars) {
    // If we split scalars by some window (let's say 8 bits), every chunk will only
    // take 256 buckets even if there are 4096 scalars, also re-uses double.
    // TODO:
    // - https://eprint.iacr.org/2024/750.pdf
    // - https://tches.iacr.org/index.php/TCHES/article/view/10287
    // 0 is accepted in scalars
    validateMSMPoints(points, c);
    validateMSMScalars(scalars, fieldN);
    const plength = points.length;
    const slength = scalars.length;
    if (plength !== slength)
        throw new Error('arrays of points and scalars must have equal length');
    // if (plength === 0) throw new Error('array must be of length >= 2');
    const zero = c.ZERO;
    const wbits = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitLen)(BigInt(plength));
    let windowSize = 1; // bits
    if (wbits > 12)
        windowSize = wbits - 3;
    else if (wbits > 4)
        windowSize = wbits - 2;
    else if (wbits > 0)
        windowSize = 2;
    const MASK = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitMask)(windowSize);
    const buckets = new Array(Number(MASK) + 1).fill(zero); // +1 for zero array
    const lastBits = Math.floor((fieldN.BITS - 1) / windowSize) * windowSize;
    let sum = zero;
    for (let i = lastBits; i >= 0; i -= windowSize) {
        buckets.fill(zero);
        for (let j = 0; j < slength; j++) {
            const scalar = scalars[j];
            const wbits = Number((scalar >> BigInt(i)) & MASK);
            buckets[wbits] = buckets[wbits].add(points[j]);
        }
        let resI = zero; // not using this will do small speed-up, but will lose ct
        // Skip first bucket, because it is zero
        for (let j = buckets.length - 1, sumI = zero; j > 0; j--) {
            sumI = sumI.add(buckets[j]);
            resI = resI.add(sumI);
        }
        sum = sum.add(resI);
        if (i !== 0)
            for (let j = 0; j < windowSize; j++)
                sum = sum.double();
    }
    return sum;
}
/**
 * Precomputed multi-scalar multiplication (MSM, Pa + Qb + Rc + ...).
 * @param c Curve Point constructor
 * @param fieldN field over CURVE.N - important that it's not over CURVE.P
 * @param points array of L curve points
 * @returns function which multiplies points with scaars
 */
function precomputeMSMUnsafe(c, fieldN, points, windowSize) {
    /**
     * Performance Analysis of Window-based Precomputation
     *
     * Base Case (256-bit scalar, 8-bit window):
     * - Standard precomputation requires:
     *   - 31 additions per scalar × 256 scalars = 7,936 ops
     *   - Plus 255 summary additions = 8,191 total ops
     *   Note: Summary additions can be optimized via accumulator
     *
     * Chunked Precomputation Analysis:
     * - Using 32 chunks requires:
     *   - 255 additions per chunk
     *   - 256 doublings
     *   - Total: (255 × 32) + 256 = 8,416 ops
     *
     * Memory Usage Comparison:
     * Window Size | Standard Points | Chunked Points
     * ------------|-----------------|---------------
     *     4-bit   |     520         |      15
     *     8-bit   |    4,224        |     255
     *    10-bit   |   13,824        |   1,023
     *    16-bit   |  557,056        |  65,535
     *
     * Key Advantages:
     * 1. Enables larger window sizes due to reduced memory overhead
     * 2. More efficient for smaller scalar counts:
     *    - 16 chunks: (16 × 255) + 256 = 4,336 ops
     *    - ~2x faster than standard 8,191 ops
     *
     * Limitations:
     * - Not suitable for plain precomputes (requires 256 constant doublings)
     * - Performance degrades with larger scalar counts:
     *   - Optimal for ~256 scalars
     *   - Less efficient for 4096+ scalars (Pippenger preferred)
     */
    validateW(windowSize, fieldN.BITS);
    validateMSMPoints(points, c);
    const zero = c.ZERO;
    const tableSize = 2 ** windowSize - 1; // table size (without zero)
    const chunks = Math.ceil(fieldN.BITS / windowSize); // chunks of item
    const MASK = bitMask(windowSize);
    const tables = points.map((p) => {
        const res = [];
        for (let i = 0, acc = p; i < tableSize; i++) {
            res.push(acc);
            acc = acc.add(p);
        }
        return res;
    });
    return (scalars) => {
        validateMSMScalars(scalars, fieldN);
        if (scalars.length > points.length)
            throw new Error('array of scalars must be smaller than array of points');
        let res = zero;
        for (let i = 0; i < chunks; i++) {
            // No need to double if accumulator is still zero.
            if (res !== zero)
                for (let j = 0; j < windowSize; j++)
                    res = res.double();
            const shiftBy = BigInt(chunks * windowSize - (i + 1) * windowSize);
            for (let j = 0; j < scalars.length; j++) {
                const n = scalars[j];
                const curr = Number((n >> shiftBy) & MASK);
                if (!curr)
                    continue; // skip zero scalars chunks
                res = res.add(tables[j][curr - 1]);
            }
        }
        return res;
    };
}
// TODO: remove
/** @deprecated */
function validateBasic(curve) {
    validateField(curve.Fp);
    validateObject(curve, {
        n: 'bigint',
        h: 'bigint',
        Gx: 'field',
        Gy: 'field',
    }, {
        nBitLength: 'isSafeInteger',
        nByteLength: 'isSafeInteger',
    });
    // Set defaults
    return Object.freeze({
        ...nLength(curve.n, curve.nBitLength),
        ...curve,
        ...{ p: curve.Fp.ORDER },
    });
}
function createField(order, field, isLE) {
    if (field) {
        if (field.ORDER !== order)
            throw new Error('Field.ORDER must match order: Fp == p, Fn == n');
        (0,_modular_js__WEBPACK_IMPORTED_MODULE_1__.validateField)(field);
        return field;
    }
    else {
        return (0,_modular_js__WEBPACK_IMPORTED_MODULE_1__.Field)(order, { isLE });
    }
}
/** Validates CURVE opts and creates fields */
function _createCurveFields(type, CURVE, curveOpts = {}, FpFnLE) {
    if (FpFnLE === undefined)
        FpFnLE = type === 'edwards';
    if (!CURVE || typeof CURVE !== 'object')
        throw new Error(`expected valid ${type} CURVE object`);
    for (const p of ['p', 'n', 'h']) {
        const val = CURVE[p];
        if (!(typeof val === 'bigint' && val > _0n))
            throw new Error(`CURVE.${p} must be positive bigint`);
    }
    const Fp = createField(CURVE.p, curveOpts.Fp, FpFnLE);
    const Fn = createField(CURVE.n, curveOpts.Fn, FpFnLE);
    const _b = type === 'weierstrass' ? 'b' : 'd';
    const params = ['Gx', 'Gy', 'a', _b];
    for (const p of params) {
        // @ts-ignore
        if (!Fp.isValid(CURVE[p]))
            throw new Error(`CURVE.${p} must be valid field element of CURVE.Fp`);
    }
    CURVE = Object.freeze(Object.assign({}, CURVE));
    return { CURVE, Fp, Fn };
}
//# sourceMappingURL=curve.js.map

/***/ },

/***/ 30
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Field: () => (/* binding */ Field),
/* harmony export */   FpInvertBatch: () => (/* binding */ FpInvertBatch),
/* harmony export */   getMinHashLength: () => (/* binding */ getMinHashLength),
/* harmony export */   mapHashToField: () => (/* binding */ mapHashToField),
/* harmony export */   mod: () => (/* binding */ mod),
/* harmony export */   nLength: () => (/* binding */ nLength),
/* harmony export */   pow2: () => (/* binding */ pow2),
/* harmony export */   validateField: () => (/* binding */ validateField)
/* harmony export */ });
/* unused harmony exports pow, invert, tonelliShanks, FpSqrt, isNegativeLE, FpPow, FpDiv, FpLegendre, FpIsSquare, FpSqrtOdd, FpSqrtEven, hashToPrivateScalar, getFieldBytesLength */
/* unused harmony import specifier */ var ensureBytes;
/* unused harmony import specifier */ var bytesToNumberLE;
/* unused harmony import specifier */ var bytesToNumberBE;
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(848);
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(976);
/**
 * Utils for modular division and fields.
 * Field over 11 is a finite (Galois) field is integer number operations `mod 11`.
 * There is no division: it is replaced by modular multiplicative inverse.
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */

// prettier-ignore
const _0n = BigInt(0), _1n = BigInt(1), _2n = /* @__PURE__ */ BigInt(2), _3n = /* @__PURE__ */ BigInt(3);
// prettier-ignore
const _4n = /* @__PURE__ */ BigInt(4), _5n = /* @__PURE__ */ BigInt(5), _7n = /* @__PURE__ */ BigInt(7);
// prettier-ignore
const _8n = /* @__PURE__ */ BigInt(8), _9n = /* @__PURE__ */ BigInt(9), _16n = /* @__PURE__ */ BigInt(16);
// Calculates a modulo b
function mod(a, b) {
    const result = a % b;
    return result >= _0n ? result : b + result;
}
/**
 * Efficiently raise num to power and do modular division.
 * Unsafe in some contexts: uses ladder, so can expose bigint bits.
 * @example
 * pow(2n, 6n, 11n) // 64n % 11n == 9n
 */
function pow(num, power, modulo) {
    return FpPow(Field(modulo), num, power);
}
/** Does `x^(2^power)` mod p. `pow2(30, 4)` == `30^(2^4)` */
function pow2(x, power, modulo) {
    let res = x;
    while (power-- > _0n) {
        res *= res;
        res %= modulo;
    }
    return res;
}
/**
 * Inverses number over modulo.
 * Implemented using [Euclidean GCD](https://brilliant.org/wiki/extended-euclidean-algorithm/).
 */
function invert(number, modulo) {
    if (number === _0n)
        throw new Error('invert: expected non-zero number');
    if (modulo <= _0n)
        throw new Error('invert: expected positive modulus, got ' + modulo);
    // Fermat's little theorem "CT-like" version inv(n) = n^(m-2) mod m is 30x slower.
    let a = mod(number, modulo);
    let b = modulo;
    // prettier-ignore
    let x = _0n, y = _1n, u = _1n, v = _0n;
    while (a !== _0n) {
        // JIT applies optimization if those two lines follow each other
        const q = b / a;
        const r = b % a;
        const m = x - u * q;
        const n = y - v * q;
        // prettier-ignore
        b = a, a = r, x = u, y = v, u = m, v = n;
    }
    const gcd = b;
    if (gcd !== _1n)
        throw new Error('invert: does not exist');
    return mod(x, modulo);
}
function assertIsSquare(Fp, root, n) {
    if (!Fp.eql(Fp.sqr(root), n))
        throw new Error('Cannot find square root');
}
// Not all roots are possible! Example which will throw:
// const NUM =
// n = 72057594037927816n;
// Fp = Field(BigInt('0x1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab'));
function sqrt3mod4(Fp, n) {
    const p1div4 = (Fp.ORDER + _1n) / _4n;
    const root = Fp.pow(n, p1div4);
    assertIsSquare(Fp, root, n);
    return root;
}
function sqrt5mod8(Fp, n) {
    const p5div8 = (Fp.ORDER - _5n) / _8n;
    const n2 = Fp.mul(n, _2n);
    const v = Fp.pow(n2, p5div8);
    const nv = Fp.mul(n, v);
    const i = Fp.mul(Fp.mul(nv, _2n), v);
    const root = Fp.mul(nv, Fp.sub(i, Fp.ONE));
    assertIsSquare(Fp, root, n);
    return root;
}
// Based on RFC9380, Kong algorithm
// prettier-ignore
function sqrt9mod16(P) {
    const Fp_ = Field(P);
    const tn = tonelliShanks(P);
    const c1 = tn(Fp_, Fp_.neg(Fp_.ONE)); //  1. c1 = sqrt(-1) in F, i.e., (c1^2) == -1 in F
    const c2 = tn(Fp_, c1); //  2. c2 = sqrt(c1) in F, i.e., (c2^2) == c1 in F
    const c3 = tn(Fp_, Fp_.neg(c1)); //  3. c3 = sqrt(-c1) in F, i.e., (c3^2) == -c1 in F
    const c4 = (P + _7n) / _16n; //  4. c4 = (q + 7) / 16        # Integer arithmetic
    return (Fp, n) => {
        let tv1 = Fp.pow(n, c4); //  1. tv1 = x^c4
        let tv2 = Fp.mul(tv1, c1); //  2. tv2 = c1 * tv1
        const tv3 = Fp.mul(tv1, c2); //  3. tv3 = c2 * tv1
        const tv4 = Fp.mul(tv1, c3); //  4. tv4 = c3 * tv1
        const e1 = Fp.eql(Fp.sqr(tv2), n); //  5.  e1 = (tv2^2) == x
        const e2 = Fp.eql(Fp.sqr(tv3), n); //  6.  e2 = (tv3^2) == x
        tv1 = Fp.cmov(tv1, tv2, e1); //  7. tv1 = CMOV(tv1, tv2, e1)  # Select tv2 if (tv2^2) == x
        tv2 = Fp.cmov(tv4, tv3, e2); //  8. tv2 = CMOV(tv4, tv3, e2)  # Select tv3 if (tv3^2) == x
        const e3 = Fp.eql(Fp.sqr(tv2), n); //  9.  e3 = (tv2^2) == x
        const root = Fp.cmov(tv1, tv2, e3); // 10.  z = CMOV(tv1, tv2, e3)   # Select sqrt from tv1 & tv2
        assertIsSquare(Fp, root, n);
        return root;
    };
}
/**
 * Tonelli-Shanks square root search algorithm.
 * 1. https://eprint.iacr.org/2012/685.pdf (page 12)
 * 2. Square Roots from 1; 24, 51, 10 to Dan Shanks
 * @param P field order
 * @returns function that takes field Fp (created from P) and number n
 */
function tonelliShanks(P) {
    // Initialization (precomputation).
    // Caching initialization could boost perf by 7%.
    if (P < _3n)
        throw new Error('sqrt is not defined for small field');
    // Factor P - 1 = Q * 2^S, where Q is odd
    let Q = P - _1n;
    let S = 0;
    while (Q % _2n === _0n) {
        Q /= _2n;
        S++;
    }
    // Find the first quadratic non-residue Z >= 2
    let Z = _2n;
    const _Fp = Field(P);
    while (FpLegendre(_Fp, Z) === 1) {
        // Basic primality test for P. After x iterations, chance of
        // not finding quadratic non-residue is 2^x, so 2^1000.
        if (Z++ > 1000)
            throw new Error('Cannot find square root: probably non-prime P');
    }
    // Fast-path; usually done before Z, but we do "primality test".
    if (S === 1)
        return sqrt3mod4;
    // Slow-path
    // TODO: test on Fp2 and others
    let cc = _Fp.pow(Z, Q); // c = z^Q
    const Q1div2 = (Q + _1n) / _2n;
    return function tonelliSlow(Fp, n) {
        if (Fp.is0(n))
            return n;
        // Check if n is a quadratic residue using Legendre symbol
        if (FpLegendre(Fp, n) !== 1)
            throw new Error('Cannot find square root');
        // Initialize variables for the main loop
        let M = S;
        let c = Fp.mul(Fp.ONE, cc); // c = z^Q, move cc from field _Fp into field Fp
        let t = Fp.pow(n, Q); // t = n^Q, first guess at the fudge factor
        let R = Fp.pow(n, Q1div2); // R = n^((Q+1)/2), first guess at the square root
        // Main loop
        // while t != 1
        while (!Fp.eql(t, Fp.ONE)) {
            if (Fp.is0(t))
                return Fp.ZERO; // if t=0 return R=0
            let i = 1;
            // Find the smallest i >= 1 such that t^(2^i) ≡ 1 (mod P)
            let t_tmp = Fp.sqr(t); // t^(2^1)
            while (!Fp.eql(t_tmp, Fp.ONE)) {
                i++;
                t_tmp = Fp.sqr(t_tmp); // t^(2^2)...
                if (i === M)
                    throw new Error('Cannot find square root');
            }
            // Calculate the exponent for b: 2^(M - i - 1)
            const exponent = _1n << BigInt(M - i - 1); // bigint is important
            const b = Fp.pow(c, exponent); // b = 2^(M - i - 1)
            // Update variables
            M = i;
            c = Fp.sqr(b); // c = b^2
            t = Fp.mul(t, c); // t = (t * b^2)
            R = Fp.mul(R, b); // R = R*b
        }
        return R;
    };
}
/**
 * Square root for a finite field. Will try optimized versions first:
 *
 * 1. P ≡ 3 (mod 4)
 * 2. P ≡ 5 (mod 8)
 * 3. P ≡ 9 (mod 16)
 * 4. Tonelli-Shanks algorithm
 *
 * Different algorithms can give different roots, it is up to user to decide which one they want.
 * For example there is FpSqrtOdd/FpSqrtEven to choice root based on oddness (used for hash-to-curve).
 */
function FpSqrt(P) {
    // P ≡ 3 (mod 4) => √n = n^((P+1)/4)
    if (P % _4n === _3n)
        return sqrt3mod4;
    // P ≡ 5 (mod 8) => Atkin algorithm, page 10 of https://eprint.iacr.org/2012/685.pdf
    if (P % _8n === _5n)
        return sqrt5mod8;
    // P ≡ 9 (mod 16) => Kong algorithm, page 11 of https://eprint.iacr.org/2012/685.pdf (algorithm 4)
    if (P % _16n === _9n)
        return sqrt9mod16(P);
    // Tonelli-Shanks algorithm
    return tonelliShanks(P);
}
// Little-endian check for first LE bit (last BE bit);
const isNegativeLE = (num, modulo) => (mod(num, modulo) & _1n) === _1n;
// prettier-ignore
const FIELD_FIELDS = [
    'create', 'isValid', 'is0', 'neg', 'inv', 'sqrt', 'sqr',
    'eql', 'add', 'sub', 'mul', 'pow', 'div',
    'addN', 'subN', 'mulN', 'sqrN'
];
function validateField(field) {
    const initial = {
        ORDER: 'bigint',
        MASK: 'bigint',
        BYTES: 'number',
        BITS: 'number',
    };
    const opts = FIELD_FIELDS.reduce((map, val) => {
        map[val] = 'function';
        return map;
    }, initial);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__._validateObject)(field, opts);
    // const max = 16384;
    // if (field.BYTES < 1 || field.BYTES > max) throw new Error('invalid field');
    // if (field.BITS < 1 || field.BITS > 8 * max) throw new Error('invalid field');
    return field;
}
// Generic field functions
/**
 * Same as `pow` but for Fp: non-constant-time.
 * Unsafe in some contexts: uses ladder, so can expose bigint bits.
 */
function FpPow(Fp, num, power) {
    if (power < _0n)
        throw new Error('invalid exponent, negatives unsupported');
    if (power === _0n)
        return Fp.ONE;
    if (power === _1n)
        return num;
    let p = Fp.ONE;
    let d = num;
    while (power > _0n) {
        if (power & _1n)
            p = Fp.mul(p, d);
        d = Fp.sqr(d);
        power >>= _1n;
    }
    return p;
}
/**
 * Efficiently invert an array of Field elements.
 * Exception-free. Will return `undefined` for 0 elements.
 * @param passZero map 0 to 0 (instead of undefined)
 */
function FpInvertBatch(Fp, nums, passZero = false) {
    const inverted = new Array(nums.length).fill(passZero ? Fp.ZERO : undefined);
    // Walk from first to last, multiply them by each other MOD p
    const multipliedAcc = nums.reduce((acc, num, i) => {
        if (Fp.is0(num))
            return acc;
        inverted[i] = acc;
        return Fp.mul(acc, num);
    }, Fp.ONE);
    // Invert last element
    const invertedAcc = Fp.inv(multipliedAcc);
    // Walk from last to first, multiply them by inverted each other MOD p
    nums.reduceRight((acc, num, i) => {
        if (Fp.is0(num))
            return acc;
        inverted[i] = Fp.mul(acc, inverted[i]);
        return Fp.mul(acc, num);
    }, invertedAcc);
    return inverted;
}
// TODO: remove
function FpDiv(Fp, lhs, rhs) {
    return Fp.mul(lhs, typeof rhs === 'bigint' ? invert(rhs, Fp.ORDER) : Fp.inv(rhs));
}
/**
 * Legendre symbol.
 * Legendre constant is used to calculate Legendre symbol (a | p)
 * which denotes the value of a^((p-1)/2) (mod p).
 *
 * * (a | p) ≡ 1    if a is a square (mod p), quadratic residue
 * * (a | p) ≡ -1   if a is not a square (mod p), quadratic non residue
 * * (a | p) ≡ 0    if a ≡ 0 (mod p)
 */
function FpLegendre(Fp, n) {
    // We can use 3rd argument as optional cache of this value
    // but seems unneeded for now. The operation is very fast.
    const p1mod2 = (Fp.ORDER - _1n) / _2n;
    const powered = Fp.pow(n, p1mod2);
    const yes = Fp.eql(powered, Fp.ONE);
    const zero = Fp.eql(powered, Fp.ZERO);
    const no = Fp.eql(powered, Fp.neg(Fp.ONE));
    if (!yes && !zero && !no)
        throw new Error('invalid Legendre symbol result');
    return yes ? 1 : zero ? 0 : -1;
}
// This function returns True whenever the value x is a square in the field F.
function FpIsSquare(Fp, n) {
    const l = FpLegendre(Fp, n);
    return l === 1;
}
// CURVE.n lengths
function nLength(n, nBitLength) {
    // Bit size, byte size of CURVE.n
    if (nBitLength !== undefined)
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.anumber)(nBitLength);
    const _nBitLength = nBitLength !== undefined ? nBitLength : n.toString(2).length;
    const nByteLength = Math.ceil(_nBitLength / 8);
    return { nBitLength: _nBitLength, nByteLength };
}
/**
 * Creates a finite field. Major performance optimizations:
 * * 1. Denormalized operations like mulN instead of mul.
 * * 2. Identical object shape: never add or remove keys.
 * * 3. `Object.freeze`.
 * Fragile: always run a benchmark on a change.
 * Security note: operations don't check 'isValid' for all elements for performance reasons,
 * it is caller responsibility to check this.
 * This is low-level code, please make sure you know what you're doing.
 *
 * Note about field properties:
 * * CHARACTERISTIC p = prime number, number of elements in main subgroup.
 * * ORDER q = similar to cofactor in curves, may be composite `q = p^m`.
 *
 * @param ORDER field order, probably prime, or could be composite
 * @param bitLen how many bits the field consumes
 * @param isLE (default: false) if encoding / decoding should be in little-endian
 * @param redef optional faster redefinitions of sqrt and other methods
 */
function Field(ORDER, bitLenOrOpts, // TODO: use opts only in v2?
isLE = false, opts = {}) {
    if (ORDER <= _0n)
        throw new Error('invalid field: expected ORDER > 0, got ' + ORDER);
    let _nbitLength = undefined;
    let _sqrt = undefined;
    let modFromBytes = false;
    let allowedLengths = undefined;
    if (typeof bitLenOrOpts === 'object' && bitLenOrOpts != null) {
        if (opts.sqrt || isLE)
            throw new Error('cannot specify opts in two arguments');
        const _opts = bitLenOrOpts;
        if (_opts.BITS)
            _nbitLength = _opts.BITS;
        if (_opts.sqrt)
            _sqrt = _opts.sqrt;
        if (typeof _opts.isLE === 'boolean')
            isLE = _opts.isLE;
        if (typeof _opts.modFromBytes === 'boolean')
            modFromBytes = _opts.modFromBytes;
        allowedLengths = _opts.allowedLengths;
    }
    else {
        if (typeof bitLenOrOpts === 'number')
            _nbitLength = bitLenOrOpts;
        if (opts.sqrt)
            _sqrt = opts.sqrt;
    }
    const { nBitLength: BITS, nByteLength: BYTES } = nLength(ORDER, _nbitLength);
    if (BYTES > 2048)
        throw new Error('invalid field: expected ORDER of <= 2048 bytes');
    let sqrtP; // cached sqrtP
    const f = Object.freeze({
        ORDER,
        isLE,
        BITS,
        BYTES,
        MASK: (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitMask)(BITS),
        ZERO: _0n,
        ONE: _1n,
        allowedLengths: allowedLengths,
        create: (num) => mod(num, ORDER),
        isValid: (num) => {
            if (typeof num !== 'bigint')
                throw new Error('invalid field element: expected bigint, got ' + typeof num);
            return _0n <= num && num < ORDER; // 0 is valid element, but it's not invertible
        },
        is0: (num) => num === _0n,
        // is valid and invertible
        isValidNot0: (num) => !f.is0(num) && f.isValid(num),
        isOdd: (num) => (num & _1n) === _1n,
        neg: (num) => mod(-num, ORDER),
        eql: (lhs, rhs) => lhs === rhs,
        sqr: (num) => mod(num * num, ORDER),
        add: (lhs, rhs) => mod(lhs + rhs, ORDER),
        sub: (lhs, rhs) => mod(lhs - rhs, ORDER),
        mul: (lhs, rhs) => mod(lhs * rhs, ORDER),
        pow: (num, power) => FpPow(f, num, power),
        div: (lhs, rhs) => mod(lhs * invert(rhs, ORDER), ORDER),
        // Same as above, but doesn't normalize
        sqrN: (num) => num * num,
        addN: (lhs, rhs) => lhs + rhs,
        subN: (lhs, rhs) => lhs - rhs,
        mulN: (lhs, rhs) => lhs * rhs,
        inv: (num) => invert(num, ORDER),
        sqrt: _sqrt ||
            ((n) => {
                if (!sqrtP)
                    sqrtP = FpSqrt(ORDER);
                return sqrtP(f, n);
            }),
        toBytes: (num) => (isLE ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesLE)(num, BYTES) : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesBE)(num, BYTES)),
        fromBytes: (bytes, skipValidation = true) => {
            if (allowedLengths) {
                if (!allowedLengths.includes(bytes.length) || bytes.length > BYTES) {
                    throw new Error('Field.fromBytes: expected ' + allowedLengths + ' bytes, got ' + bytes.length);
                }
                const padded = new Uint8Array(BYTES);
                // isLE add 0 to right, !isLE to the left.
                padded.set(bytes, isLE ? 0 : padded.length - bytes.length);
                bytes = padded;
            }
            if (bytes.length !== BYTES)
                throw new Error('Field.fromBytes: expected ' + BYTES + ' bytes, got ' + bytes.length);
            let scalar = isLE ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberLE)(bytes) : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)(bytes);
            if (modFromBytes)
                scalar = mod(scalar, ORDER);
            if (!skipValidation)
                if (!f.isValid(scalar))
                    throw new Error('invalid field element: outside of range 0..ORDER');
            // NOTE: we don't validate scalar here, please use isValid. This done such way because some
            // protocol may allow non-reduced scalar that reduced later or changed some other way.
            return scalar;
        },
        // TODO: we don't need it here, move out to separate fn
        invertBatch: (lst) => FpInvertBatch(f, lst),
        // We can't move this out because Fp6, Fp12 implement it
        // and it's unclear what to return in there.
        cmov: (a, b, c) => (c ? b : a),
    });
    return Object.freeze(f);
}
// Generic random scalar, we can do same for other fields if via Fp2.mul(Fp2.ONE, Fp2.random)?
// This allows unsafe methods like ignore bias or zero. These unsafe, but often used in different protocols (if deterministic RNG).
// which mean we cannot force this via opts.
// Not sure what to do with randomBytes, we can accept it inside opts if wanted.
// Probably need to export getMinHashLength somewhere?
// random(bytes?: Uint8Array, unsafeAllowZero = false, unsafeAllowBias = false) {
//   const LEN = !unsafeAllowBias ? getMinHashLength(ORDER) : BYTES;
//   if (bytes === undefined) bytes = randomBytes(LEN); // _opts.randomBytes?
//   const num = isLE ? bytesToNumberLE(bytes) : bytesToNumberBE(bytes);
//   // `mod(x, 11)` can sometimes produce 0. `mod(x, 10) + 1` is the same, but no 0
//   const reduced = unsafeAllowZero ? mod(num, ORDER) : mod(num, ORDER - _1n) + _1n;
//   return reduced;
// },
function FpSqrtOdd(Fp, elm) {
    if (!Fp.isOdd)
        throw new Error("Field doesn't have isOdd");
    const root = Fp.sqrt(elm);
    return Fp.isOdd(root) ? root : Fp.neg(root);
}
function FpSqrtEven(Fp, elm) {
    if (!Fp.isOdd)
        throw new Error("Field doesn't have isOdd");
    const root = Fp.sqrt(elm);
    return Fp.isOdd(root) ? Fp.neg(root) : root;
}
/**
 * "Constant-time" private key generation utility.
 * Same as mapKeyToField, but accepts less bytes (40 instead of 48 for 32-byte field).
 * Which makes it slightly more biased, less secure.
 * @deprecated use `mapKeyToField` instead
 */
function hashToPrivateScalar(hash, groupOrder, isLE = false) {
    hash = ensureBytes('privateHash', hash);
    const hashLen = hash.length;
    const minLen = nLength(groupOrder).nByteLength + 8;
    if (minLen < 24 || hashLen < minLen || hashLen > 1024)
        throw new Error('hashToPrivateScalar: expected ' + minLen + '-1024 bytes of input, got ' + hashLen);
    const num = isLE ? bytesToNumberLE(hash) : bytesToNumberBE(hash);
    return mod(num, groupOrder - _1n) + _1n;
}
/**
 * Returns total number of bytes consumed by the field element.
 * For example, 32 bytes for usual 256-bit weierstrass curve.
 * @param fieldOrder number of field elements, usually CURVE.n
 * @returns byte length of field
 */
function getFieldBytesLength(fieldOrder) {
    if (typeof fieldOrder !== 'bigint')
        throw new Error('field order must be bigint');
    const bitLength = fieldOrder.toString(2).length;
    return Math.ceil(bitLength / 8);
}
/**
 * Returns minimal amount of bytes that can be safely reduced
 * by field order.
 * Should be 2^-128 for 128-bit curve such as P256.
 * @param fieldOrder number of field elements, usually CURVE.n
 * @returns byte length of target hash
 */
function getMinHashLength(fieldOrder) {
    const length = getFieldBytesLength(fieldOrder);
    return length + Math.ceil(length / 2);
}
/**
 * "Constant-time" private key generation utility.
 * Can take (n + n/2) or more bytes of uniform input e.g. from CSPRNG or KDF
 * and convert them into private scalar, with the modulo bias being negligible.
 * Needs at least 48 bytes of input for 32-byte private key.
 * https://research.kudelskisecurity.com/2020/07/28/the-definitive-guide-to-modulo-bias-and-how-to-avoid-it/
 * FIPS 186-5, A.2 https://csrc.nist.gov/publications/detail/fips/186/5/final
 * RFC 9380, https://www.rfc-editor.org/rfc/rfc9380#section-5
 * @param hash hash output from SHA3 or a similar function
 * @param groupOrder size of subgroup - (e.g. secp256k1.CURVE.n)
 * @param isLE interpret hash bytes as LE num
 * @returns valid private scalar
 */
function mapHashToField(key, fieldOrder, isLE = false) {
    const len = key.length;
    const fieldLen = getFieldBytesLength(fieldOrder);
    const minLen = getMinHashLength(fieldOrder);
    // No small numbers: need to understand bias story. No huge numbers: easier to detect JS timings.
    if (len < 16 || len < minLen || len > 1024)
        throw new Error('expected ' + minLen + '-1024 bytes of input, got ' + len);
    const num = isLE ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberLE)(key) : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)(key);
    // `mod(x, 11)` can sometimes produce 0. `mod(x, 10) + 1` is the same, but no 0
    const reduced = mod(num, fieldOrder - _1n) + _1n;
    return isLE ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesLE)(reduced, fieldLen) : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesBE)(reduced, fieldLen);
}
//# sourceMappingURL=modular.js.map

/***/ },

/***/ 316
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   _normFnElement: () => (/* binding */ _normFnElement),
/* harmony export */   weierstrass: () => (/* binding */ weierstrass)
/* harmony export */ });
/* unused harmony exports _splitEndoScalar, DERErr, DER, weierstrassN, SWUFpSqrtRatio, mapToCurveSimpleSWU, ecdh, ecdsa, weierstrassPoints, _legacyHelperEquat */
/* unused harmony import specifier */ var inRange;
/* unused harmony import specifier */ var validateField;
/* unused harmony import specifier */ var FpInvertBatch;
/* harmony import */ var _noble_hashes_hmac_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(454);
/* harmony import */ var _noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(976);
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(848);
/* harmony import */ var _curve_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(615);
/* harmony import */ var _modular_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(30);
/**
 * Short Weierstrass curve methods. The formula is: y² = x³ + ax + b.
 *
 * ### Design rationale for types
 *
 * * Interaction between classes from different curves should fail:
 *   `k256.Point.BASE.add(p256.Point.BASE)`
 * * For this purpose we want to use `instanceof` operator, which is fast and works during runtime
 * * Different calls of `curve()` would return different classes -
 *   `curve(params) !== curve(params)`: if somebody decided to monkey-patch their curve,
 *   it won't affect others
 *
 * TypeScript can't infer types for classes created inside a function. Classes is one instance
 * of nominative types in TypeScript and interfaces only check for shape, so it's hard to create
 * unique type for every function call.
 *
 * We can use generic types via some param, like curve opts, but that would:
 *     1. Enable interaction between `curve(params)` and `curve(params)` (curves of same params)
 *     which is hard to debug.
 *     2. Params can be generic and we can't enforce them to be constant value:
 *     if somebody creates curve from non-constant params,
 *     it would be allowed to interact with other curves with non-constant params
 *
 * @todo https://www.typescriptlang.org/docs/handbook/release-notes/typescript-2-7.html#unique-symbol
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */





// We construct basis in such way that den is always positive and equals n, but num sign depends on basis (not on secret value)
const divNearest = (num, den) => (num + (num >= 0 ? den : -den) / _2n) / den;
/**
 * Splits scalar for GLV endomorphism.
 */
function _splitEndoScalar(k, basis, n) {
    // Split scalar into two such that part is ~half bits: `abs(part) < sqrt(N)`
    // Since part can be negative, we need to do this on point.
    // TODO: verifyScalar function which consumes lambda
    const [[a1, b1], [a2, b2]] = basis;
    const c1 = divNearest(b2 * k, n);
    const c2 = divNearest(-b1 * k, n);
    // |k1|/|k2| is < sqrt(N), but can be negative.
    // If we do `k1 mod N`, we'll get big scalar (`> sqrt(N)`): so, we do cheaper negation instead.
    let k1 = k - c1 * a1 - c2 * a2;
    let k2 = -c1 * b1 - c2 * b2;
    const k1neg = k1 < _0n;
    const k2neg = k2 < _0n;
    if (k1neg)
        k1 = -k1;
    if (k2neg)
        k2 = -k2;
    // Double check that resulting scalar less than half bits of N: otherwise wNAF will fail.
    // This should only happen on wrong basises. Also, math inside is too complex and I don't trust it.
    const MAX_NUM = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.bitMask)(Math.ceil((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.bitLen)(n) / 2)) + _1n; // Half bits of N
    if (k1 < _0n || k1 >= MAX_NUM || k2 < _0n || k2 >= MAX_NUM) {
        throw new Error('splitScalar (endomorphism): failed, k=' + k);
    }
    return { k1neg, k1, k2neg, k2 };
}
function validateSigFormat(format) {
    if (!['compact', 'recovered', 'der'].includes(format))
        throw new Error('Signature format must be "compact", "recovered", or "der"');
    return format;
}
function validateSigOpts(opts, def) {
    const optsn = {};
    for (let optName of Object.keys(def)) {
        // @ts-ignore
        optsn[optName] = opts[optName] === undefined ? def[optName] : opts[optName];
    }
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abool2)(optsn.lowS, 'lowS');
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abool2)(optsn.prehash, 'prehash');
    if (optsn.format !== undefined)
        validateSigFormat(optsn.format);
    return optsn;
}
class DERErr extends Error {
    constructor(m = '') {
        super(m);
    }
}
/**
 * ASN.1 DER encoding utilities. ASN is very complex & fragile. Format:
 *
 *     [0x30 (SEQUENCE), bytelength, 0x02 (INTEGER), intLength, R, 0x02 (INTEGER), intLength, S]
 *
 * Docs: https://letsencrypt.org/docs/a-warm-welcome-to-asn1-and-der/, https://luca.ntop.org/Teaching/Appunti/asn1.html
 */
const DER = {
    // asn.1 DER encoding utils
    Err: DERErr,
    // Basic building block is TLV (Tag-Length-Value)
    _tlv: {
        encode: (tag, data) => {
            const { Err: E } = DER;
            if (tag < 0 || tag > 256)
                throw new E('tlv.encode: wrong tag');
            if (data.length & 1)
                throw new E('tlv.encode: unpadded data');
            const dataLen = data.length / 2;
            const len = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.numberToHexUnpadded)(dataLen);
            if ((len.length / 2) & 128)
                throw new E('tlv.encode: long form length too big');
            // length of length with long form flag
            const lenLen = dataLen > 127 ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.numberToHexUnpadded)((len.length / 2) | 128) : '';
            const t = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.numberToHexUnpadded)(tag);
            return t + lenLen + len + data;
        },
        // v - value, l - left bytes (unparsed)
        decode(tag, data) {
            const { Err: E } = DER;
            let pos = 0;
            if (tag < 0 || tag > 256)
                throw new E('tlv.encode: wrong tag');
            if (data.length < 2 || data[pos++] !== tag)
                throw new E('tlv.decode: wrong tlv');
            const first = data[pos++];
            const isLong = !!(first & 128); // First bit of first length byte is flag for short/long form
            let length = 0;
            if (!isLong)
                length = first;
            else {
                // Long form: [longFlag(1bit), lengthLength(7bit), length (BE)]
                const lenLen = first & 127;
                if (!lenLen)
                    throw new E('tlv.decode(long): indefinite length not supported');
                if (lenLen > 4)
                    throw new E('tlv.decode(long): byte length is too big'); // this will overflow u32 in js
                const lengthBytes = data.subarray(pos, pos + lenLen);
                if (lengthBytes.length !== lenLen)
                    throw new E('tlv.decode: length bytes not complete');
                if (lengthBytes[0] === 0)
                    throw new E('tlv.decode(long): zero leftmost byte');
                for (const b of lengthBytes)
                    length = (length << 8) | b;
                pos += lenLen;
                if (length < 128)
                    throw new E('tlv.decode(long): not minimal encoding');
            }
            const v = data.subarray(pos, pos + length);
            if (v.length !== length)
                throw new E('tlv.decode: wrong value length');
            return { v, l: data.subarray(pos + length) };
        },
    },
    // https://crypto.stackexchange.com/a/57734 Leftmost bit of first byte is 'negative' flag,
    // since we always use positive integers here. It must always be empty:
    // - add zero byte if exists
    // - if next byte doesn't have a flag, leading zero is not allowed (minimal encoding)
    _int: {
        encode(num) {
            const { Err: E } = DER;
            if (num < _0n)
                throw new E('integer: negative integers are not allowed');
            let hex = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.numberToHexUnpadded)(num);
            // Pad with zero byte if negative flag is present
            if (Number.parseInt(hex[0], 16) & 0b1000)
                hex = '00' + hex;
            if (hex.length & 1)
                throw new E('unexpected DER parsing assertion: unpadded hex');
            return hex;
        },
        decode(data) {
            const { Err: E } = DER;
            if (data[0] & 128)
                throw new E('invalid signature integer: negative');
            if (data[0] === 0x00 && !(data[1] & 128))
                throw new E('invalid signature integer: unnecessary leading zero');
            return (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.bytesToNumberBE)(data);
        },
    },
    toSig(hex) {
        // parse DER signature
        const { Err: E, _int: int, _tlv: tlv } = DER;
        const data = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('signature', hex);
        const { v: seqBytes, l: seqLeftBytes } = tlv.decode(0x30, data);
        if (seqLeftBytes.length)
            throw new E('invalid signature: left bytes after parsing');
        const { v: rBytes, l: rLeftBytes } = tlv.decode(0x02, seqBytes);
        const { v: sBytes, l: sLeftBytes } = tlv.decode(0x02, rLeftBytes);
        if (sLeftBytes.length)
            throw new E('invalid signature: left bytes after parsing');
        return { r: int.decode(rBytes), s: int.decode(sBytes) };
    },
    hexFromSig(sig) {
        const { _tlv: tlv, _int: int } = DER;
        const rs = tlv.encode(0x02, int.encode(sig.r));
        const ss = tlv.encode(0x02, int.encode(sig.s));
        const seq = rs + ss;
        return tlv.encode(0x30, seq);
    },
};
// Be friendly to bad ECMAScript parsers by not using bigint literals
// prettier-ignore
const _0n = BigInt(0), _1n = BigInt(1), _2n = BigInt(2), _3n = BigInt(3), _4n = BigInt(4);
function _normFnElement(Fn, key) {
    const { BYTES: expected } = Fn;
    let num;
    if (typeof key === 'bigint') {
        num = key;
    }
    else {
        let bytes = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('private key', key);
        try {
            num = Fn.fromBytes(bytes);
        }
        catch (error) {
            throw new Error(`invalid private key: expected ui8a of size ${expected}, got ${typeof key}`);
        }
    }
    if (!Fn.isValidNot0(num))
        throw new Error('invalid private key: out of range [1..N-1]');
    return num;
}
/**
 * Creates weierstrass Point constructor, based on specified curve options.
 *
 * @example
```js
const opts = {
  p: BigInt('0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff'),
  n: BigInt('0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551'),
  h: BigInt(1),
  a: BigInt('0xffffffff00000001000000000000000000000000fffffffffffffffffffffffc'),
  b: BigInt('0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b'),
  Gx: BigInt('0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296'),
  Gy: BigInt('0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5'),
};
const p256_Point = weierstrass(opts);
```
 */
function weierstrassN(params, extraOpts = {}) {
    const validated = (0,_curve_js__WEBPACK_IMPORTED_MODULE_3__._createCurveFields)('weierstrass', params, extraOpts);
    const { Fp, Fn } = validated;
    let CURVE = validated.CURVE;
    const { h: cofactor, n: CURVE_ORDER } = CURVE;
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._validateObject)(extraOpts, {}, {
        allowInfinityPoint: 'boolean',
        clearCofactor: 'function',
        isTorsionFree: 'function',
        fromBytes: 'function',
        toBytes: 'function',
        endo: 'object',
        wrapPrivateKey: 'boolean',
    });
    const { endo } = extraOpts;
    if (endo) {
        // validateObject(endo, { beta: 'bigint', splitScalar: 'function' });
        if (!Fp.is0(CURVE.a) || typeof endo.beta !== 'bigint' || !Array.isArray(endo.basises)) {
            throw new Error('invalid endo: expected "beta": bigint and "basises": array');
        }
    }
    const lengths = getWLengths(Fp, Fn);
    function assertCompressionIsSupported() {
        if (!Fp.isOdd)
            throw new Error('compression is not supported: Field does not have .isOdd()');
    }
    // Implements IEEE P1363 point encoding
    function pointToBytes(_c, point, isCompressed) {
        const { x, y } = point.toAffine();
        const bx = Fp.toBytes(x);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abool2)(isCompressed, 'isCompressed');
        if (isCompressed) {
            assertCompressionIsSupported();
            const hasEvenY = !Fp.isOdd(y);
            return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(pprefix(hasEvenY), bx);
        }
        else {
            return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(Uint8Array.of(0x04), bx, Fp.toBytes(y));
        }
    }
    function pointFromBytes(bytes) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abytes2)(bytes, undefined, 'Point');
        const { publicKey: comp, publicKeyUncompressed: uncomp } = lengths; // e.g. for 32-byte: 33, 65
        const length = bytes.length;
        const head = bytes[0];
        const tail = bytes.subarray(1);
        // No actual validation is done here: use .assertValidity()
        if (length === comp && (head === 0x02 || head === 0x03)) {
            const x = Fp.fromBytes(tail);
            if (!Fp.isValid(x))
                throw new Error('bad point: is not on curve, wrong x');
            const y2 = weierstrassEquation(x); // y² = x³ + ax + b
            let y;
            try {
                y = Fp.sqrt(y2); // y = y² ^ (p+1)/4
            }
            catch (sqrtError) {
                const err = sqrtError instanceof Error ? ': ' + sqrtError.message : '';
                throw new Error('bad point: is not on curve, sqrt error' + err);
            }
            assertCompressionIsSupported();
            const isYOdd = Fp.isOdd(y); // (y & _1n) === _1n;
            const isHeadOdd = (head & 1) === 1; // ECDSA-specific
            if (isHeadOdd !== isYOdd)
                y = Fp.neg(y);
            return { x, y };
        }
        else if (length === uncomp && head === 0x04) {
            // TODO: more checks
            const L = Fp.BYTES;
            const x = Fp.fromBytes(tail.subarray(0, L));
            const y = Fp.fromBytes(tail.subarray(L, L * 2));
            if (!isValidXY(x, y))
                throw new Error('bad point: is not on curve');
            return { x, y };
        }
        else {
            throw new Error(`bad point: got length ${length}, expected compressed=${comp} or uncompressed=${uncomp}`);
        }
    }
    const encodePoint = extraOpts.toBytes || pointToBytes;
    const decodePoint = extraOpts.fromBytes || pointFromBytes;
    function weierstrassEquation(x) {
        const x2 = Fp.sqr(x); // x * x
        const x3 = Fp.mul(x2, x); // x² * x
        return Fp.add(Fp.add(x3, Fp.mul(x, CURVE.a)), CURVE.b); // x³ + a * x + b
    }
    // TODO: move top-level
    /** Checks whether equation holds for given x, y: y² == x³ + ax + b */
    function isValidXY(x, y) {
        const left = Fp.sqr(y); // y²
        const right = weierstrassEquation(x); // x³ + ax + b
        return Fp.eql(left, right);
    }
    // Validate whether the passed curve params are valid.
    // Test 1: equation y² = x³ + ax + b should work for generator point.
    if (!isValidXY(CURVE.Gx, CURVE.Gy))
        throw new Error('bad curve params: generator point');
    // Test 2: discriminant Δ part should be non-zero: 4a³ + 27b² != 0.
    // Guarantees curve is genus-1, smooth (non-singular).
    const _4a3 = Fp.mul(Fp.pow(CURVE.a, _3n), _4n);
    const _27b2 = Fp.mul(Fp.sqr(CURVE.b), BigInt(27));
    if (Fp.is0(Fp.add(_4a3, _27b2)))
        throw new Error('bad curve params: a or b');
    /** Asserts coordinate is valid: 0 <= n < Fp.ORDER. */
    function acoord(title, n, banZero = false) {
        if (!Fp.isValid(n) || (banZero && Fp.is0(n)))
            throw new Error(`bad point coordinate ${title}`);
        return n;
    }
    function aprjpoint(other) {
        if (!(other instanceof Point))
            throw new Error('ProjectivePoint expected');
    }
    function splitEndoScalarN(k) {
        if (!endo || !endo.basises)
            throw new Error('no endo');
        return _splitEndoScalar(k, endo.basises, Fn.ORDER);
    }
    // Memoized toAffine / validity check. They are heavy. Points are immutable.
    // Converts Projective point to affine (x, y) coordinates.
    // Can accept precomputed Z^-1 - for example, from invertBatch.
    // (X, Y, Z) ∋ (x=X/Z, y=Y/Z)
    const toAffineMemo = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.memoized)((p, iz) => {
        const { X, Y, Z } = p;
        // Fast-path for normalized points
        if (Fp.eql(Z, Fp.ONE))
            return { x: X, y: Y };
        const is0 = p.is0();
        // If invZ was 0, we return zero point. However we still want to execute
        // all operations, so we replace invZ with a random number, 1.
        if (iz == null)
            iz = is0 ? Fp.ONE : Fp.inv(Z);
        const x = Fp.mul(X, iz);
        const y = Fp.mul(Y, iz);
        const zz = Fp.mul(Z, iz);
        if (is0)
            return { x: Fp.ZERO, y: Fp.ZERO };
        if (!Fp.eql(zz, Fp.ONE))
            throw new Error('invZ was invalid');
        return { x, y };
    });
    // NOTE: on exception this will crash 'cached' and no value will be set.
    // Otherwise true will be return
    const assertValidMemo = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.memoized)((p) => {
        if (p.is0()) {
            // (0, 1, 0) aka ZERO is invalid in most contexts.
            // In BLS, ZERO can be serialized, so we allow it.
            // (0, 0, 0) is invalid representation of ZERO.
            if (extraOpts.allowInfinityPoint && !Fp.is0(p.Y))
                return;
            throw new Error('bad point: ZERO');
        }
        // Some 3rd-party test vectors require different wording between here & `fromCompressedHex`
        const { x, y } = p.toAffine();
        if (!Fp.isValid(x) || !Fp.isValid(y))
            throw new Error('bad point: x or y not field elements');
        if (!isValidXY(x, y))
            throw new Error('bad point: equation left != right');
        if (!p.isTorsionFree())
            throw new Error('bad point: not in prime-order subgroup');
        return true;
    });
    function finishEndo(endoBeta, k1p, k2p, k1neg, k2neg) {
        k2p = new Point(Fp.mul(k2p.X, endoBeta), k2p.Y, k2p.Z);
        k1p = (0,_curve_js__WEBPACK_IMPORTED_MODULE_3__.negateCt)(k1neg, k1p);
        k2p = (0,_curve_js__WEBPACK_IMPORTED_MODULE_3__.negateCt)(k2neg, k2p);
        return k1p.add(k2p);
    }
    /**
     * Projective Point works in 3d / projective (homogeneous) coordinates:(X, Y, Z) ∋ (x=X/Z, y=Y/Z).
     * Default Point works in 2d / affine coordinates: (x, y).
     * We're doing calculations in projective, because its operations don't require costly inversion.
     */
    class Point {
        /** Does NOT validate if the point is valid. Use `.assertValidity()`. */
        constructor(X, Y, Z) {
            this.X = acoord('x', X);
            this.Y = acoord('y', Y, true);
            this.Z = acoord('z', Z);
            Object.freeze(this);
        }
        static CURVE() {
            return CURVE;
        }
        /** Does NOT validate if the point is valid. Use `.assertValidity()`. */
        static fromAffine(p) {
            const { x, y } = p || {};
            if (!p || !Fp.isValid(x) || !Fp.isValid(y))
                throw new Error('invalid affine point');
            if (p instanceof Point)
                throw new Error('projective point not allowed');
            // (0, 0) would've produced (0, 0, 1) - instead, we need (0, 1, 0)
            if (Fp.is0(x) && Fp.is0(y))
                return Point.ZERO;
            return new Point(x, y, Fp.ONE);
        }
        static fromBytes(bytes) {
            const P = Point.fromAffine(decodePoint((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abytes2)(bytes, undefined, 'point')));
            P.assertValidity();
            return P;
        }
        static fromHex(hex) {
            return Point.fromBytes((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('pointHex', hex));
        }
        get x() {
            return this.toAffine().x;
        }
        get y() {
            return this.toAffine().y;
        }
        /**
         *
         * @param windowSize
         * @param isLazy true will defer table computation until the first multiplication
         * @returns
         */
        precompute(windowSize = 8, isLazy = true) {
            wnaf.createCache(this, windowSize);
            if (!isLazy)
                this.multiply(_3n); // random number
            return this;
        }
        // TODO: return `this`
        /** A point on curve is valid if it conforms to equation. */
        assertValidity() {
            assertValidMemo(this);
        }
        hasEvenY() {
            const { y } = this.toAffine();
            if (!Fp.isOdd)
                throw new Error("Field doesn't support isOdd");
            return !Fp.isOdd(y);
        }
        /** Compare one point to another. */
        equals(other) {
            aprjpoint(other);
            const { X: X1, Y: Y1, Z: Z1 } = this;
            const { X: X2, Y: Y2, Z: Z2 } = other;
            const U1 = Fp.eql(Fp.mul(X1, Z2), Fp.mul(X2, Z1));
            const U2 = Fp.eql(Fp.mul(Y1, Z2), Fp.mul(Y2, Z1));
            return U1 && U2;
        }
        /** Flips point to one corresponding to (x, -y) in Affine coordinates. */
        negate() {
            return new Point(this.X, Fp.neg(this.Y), this.Z);
        }
        // Renes-Costello-Batina exception-free doubling formula.
        // There is 30% faster Jacobian formula, but it is not complete.
        // https://eprint.iacr.org/2015/1060, algorithm 3
        // Cost: 8M + 3S + 3*a + 2*b3 + 15add.
        double() {
            const { a, b } = CURVE;
            const b3 = Fp.mul(b, _3n);
            const { X: X1, Y: Y1, Z: Z1 } = this;
            let X3 = Fp.ZERO, Y3 = Fp.ZERO, Z3 = Fp.ZERO; // prettier-ignore
            let t0 = Fp.mul(X1, X1); // step 1
            let t1 = Fp.mul(Y1, Y1);
            let t2 = Fp.mul(Z1, Z1);
            let t3 = Fp.mul(X1, Y1);
            t3 = Fp.add(t3, t3); // step 5
            Z3 = Fp.mul(X1, Z1);
            Z3 = Fp.add(Z3, Z3);
            X3 = Fp.mul(a, Z3);
            Y3 = Fp.mul(b3, t2);
            Y3 = Fp.add(X3, Y3); // step 10
            X3 = Fp.sub(t1, Y3);
            Y3 = Fp.add(t1, Y3);
            Y3 = Fp.mul(X3, Y3);
            X3 = Fp.mul(t3, X3);
            Z3 = Fp.mul(b3, Z3); // step 15
            t2 = Fp.mul(a, t2);
            t3 = Fp.sub(t0, t2);
            t3 = Fp.mul(a, t3);
            t3 = Fp.add(t3, Z3);
            Z3 = Fp.add(t0, t0); // step 20
            t0 = Fp.add(Z3, t0);
            t0 = Fp.add(t0, t2);
            t0 = Fp.mul(t0, t3);
            Y3 = Fp.add(Y3, t0);
            t2 = Fp.mul(Y1, Z1); // step 25
            t2 = Fp.add(t2, t2);
            t0 = Fp.mul(t2, t3);
            X3 = Fp.sub(X3, t0);
            Z3 = Fp.mul(t2, t1);
            Z3 = Fp.add(Z3, Z3); // step 30
            Z3 = Fp.add(Z3, Z3);
            return new Point(X3, Y3, Z3);
        }
        // Renes-Costello-Batina exception-free addition formula.
        // There is 30% faster Jacobian formula, but it is not complete.
        // https://eprint.iacr.org/2015/1060, algorithm 1
        // Cost: 12M + 0S + 3*a + 3*b3 + 23add.
        add(other) {
            aprjpoint(other);
            const { X: X1, Y: Y1, Z: Z1 } = this;
            const { X: X2, Y: Y2, Z: Z2 } = other;
            let X3 = Fp.ZERO, Y3 = Fp.ZERO, Z3 = Fp.ZERO; // prettier-ignore
            const a = CURVE.a;
            const b3 = Fp.mul(CURVE.b, _3n);
            let t0 = Fp.mul(X1, X2); // step 1
            let t1 = Fp.mul(Y1, Y2);
            let t2 = Fp.mul(Z1, Z2);
            let t3 = Fp.add(X1, Y1);
            let t4 = Fp.add(X2, Y2); // step 5
            t3 = Fp.mul(t3, t4);
            t4 = Fp.add(t0, t1);
            t3 = Fp.sub(t3, t4);
            t4 = Fp.add(X1, Z1);
            let t5 = Fp.add(X2, Z2); // step 10
            t4 = Fp.mul(t4, t5);
            t5 = Fp.add(t0, t2);
            t4 = Fp.sub(t4, t5);
            t5 = Fp.add(Y1, Z1);
            X3 = Fp.add(Y2, Z2); // step 15
            t5 = Fp.mul(t5, X3);
            X3 = Fp.add(t1, t2);
            t5 = Fp.sub(t5, X3);
            Z3 = Fp.mul(a, t4);
            X3 = Fp.mul(b3, t2); // step 20
            Z3 = Fp.add(X3, Z3);
            X3 = Fp.sub(t1, Z3);
            Z3 = Fp.add(t1, Z3);
            Y3 = Fp.mul(X3, Z3);
            t1 = Fp.add(t0, t0); // step 25
            t1 = Fp.add(t1, t0);
            t2 = Fp.mul(a, t2);
            t4 = Fp.mul(b3, t4);
            t1 = Fp.add(t1, t2);
            t2 = Fp.sub(t0, t2); // step 30
            t2 = Fp.mul(a, t2);
            t4 = Fp.add(t4, t2);
            t0 = Fp.mul(t1, t4);
            Y3 = Fp.add(Y3, t0);
            t0 = Fp.mul(t5, t4); // step 35
            X3 = Fp.mul(t3, X3);
            X3 = Fp.sub(X3, t0);
            t0 = Fp.mul(t3, t1);
            Z3 = Fp.mul(t5, Z3);
            Z3 = Fp.add(Z3, t0); // step 40
            return new Point(X3, Y3, Z3);
        }
        subtract(other) {
            return this.add(other.negate());
        }
        is0() {
            return this.equals(Point.ZERO);
        }
        /**
         * Constant time multiplication.
         * Uses wNAF method. Windowed method may be 10% faster,
         * but takes 2x longer to generate and consumes 2x memory.
         * Uses precomputes when available.
         * Uses endomorphism for Koblitz curves.
         * @param scalar by which the point would be multiplied
         * @returns New point
         */
        multiply(scalar) {
            const { endo } = extraOpts;
            if (!Fn.isValidNot0(scalar))
                throw new Error('invalid scalar: out of range'); // 0 is invalid
            let point, fake; // Fake point is used to const-time mult
            const mul = (n) => wnaf.cached(this, n, (p) => (0,_curve_js__WEBPACK_IMPORTED_MODULE_3__.normalizeZ)(Point, p));
            /** See docs for {@link EndomorphismOpts} */
            if (endo) {
                const { k1neg, k1, k2neg, k2 } = splitEndoScalarN(scalar);
                const { p: k1p, f: k1f } = mul(k1);
                const { p: k2p, f: k2f } = mul(k2);
                fake = k1f.add(k2f);
                point = finishEndo(endo.beta, k1p, k2p, k1neg, k2neg);
            }
            else {
                const { p, f } = mul(scalar);
                point = p;
                fake = f;
            }
            // Normalize `z` for both points, but return only real one
            return (0,_curve_js__WEBPACK_IMPORTED_MODULE_3__.normalizeZ)(Point, [point, fake])[0];
        }
        /**
         * Non-constant-time multiplication. Uses double-and-add algorithm.
         * It's faster, but should only be used when you don't care about
         * an exposed secret key e.g. sig verification, which works over *public* keys.
         */
        multiplyUnsafe(sc) {
            const { endo } = extraOpts;
            const p = this;
            if (!Fn.isValid(sc))
                throw new Error('invalid scalar: out of range'); // 0 is valid
            if (sc === _0n || p.is0())
                return Point.ZERO;
            if (sc === _1n)
                return p; // fast-path
            if (wnaf.hasCache(this))
                return this.multiply(sc);
            if (endo) {
                const { k1neg, k1, k2neg, k2 } = splitEndoScalarN(sc);
                const { p1, p2 } = (0,_curve_js__WEBPACK_IMPORTED_MODULE_3__.mulEndoUnsafe)(Point, p, k1, k2); // 30% faster vs wnaf.unsafe
                return finishEndo(endo.beta, p1, p2, k1neg, k2neg);
            }
            else {
                return wnaf.unsafe(p, sc);
            }
        }
        multiplyAndAddUnsafe(Q, a, b) {
            const sum = this.multiplyUnsafe(a).add(Q.multiplyUnsafe(b));
            return sum.is0() ? undefined : sum;
        }
        /**
         * Converts Projective point to affine (x, y) coordinates.
         * @param invertedZ Z^-1 (inverted zero) - optional, precomputation is useful for invertBatch
         */
        toAffine(invertedZ) {
            return toAffineMemo(this, invertedZ);
        }
        /**
         * Checks whether Point is free of torsion elements (is in prime subgroup).
         * Always torsion-free for cofactor=1 curves.
         */
        isTorsionFree() {
            const { isTorsionFree } = extraOpts;
            if (cofactor === _1n)
                return true;
            if (isTorsionFree)
                return isTorsionFree(Point, this);
            return wnaf.unsafe(this, CURVE_ORDER).is0();
        }
        clearCofactor() {
            const { clearCofactor } = extraOpts;
            if (cofactor === _1n)
                return this; // Fast-path
            if (clearCofactor)
                return clearCofactor(Point, this);
            return this.multiplyUnsafe(cofactor);
        }
        isSmallOrder() {
            // can we use this.clearCofactor()?
            return this.multiplyUnsafe(cofactor).is0();
        }
        toBytes(isCompressed = true) {
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abool2)(isCompressed, 'isCompressed');
            this.assertValidity();
            return encodePoint(Point, this, isCompressed);
        }
        toHex(isCompressed = true) {
            return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.bytesToHex)(this.toBytes(isCompressed));
        }
        toString() {
            return `<Point ${this.is0() ? 'ZERO' : this.toHex()}>`;
        }
        // TODO: remove
        get px() {
            return this.X;
        }
        get py() {
            return this.X;
        }
        get pz() {
            return this.Z;
        }
        toRawBytes(isCompressed = true) {
            return this.toBytes(isCompressed);
        }
        _setWindowSize(windowSize) {
            this.precompute(windowSize);
        }
        static normalizeZ(points) {
            return (0,_curve_js__WEBPACK_IMPORTED_MODULE_3__.normalizeZ)(Point, points);
        }
        static msm(points, scalars) {
            return (0,_curve_js__WEBPACK_IMPORTED_MODULE_3__.pippenger)(Point, Fn, points, scalars);
        }
        static fromPrivateKey(privateKey) {
            return Point.BASE.multiply(_normFnElement(Fn, privateKey));
        }
    }
    // base / generator point
    Point.BASE = new Point(CURVE.Gx, CURVE.Gy, Fp.ONE);
    // zero / infinity / identity point
    Point.ZERO = new Point(Fp.ZERO, Fp.ONE, Fp.ZERO); // 0, 1, 0
    // math field
    Point.Fp = Fp;
    // scalar field
    Point.Fn = Fn;
    const bits = Fn.BITS;
    const wnaf = new _curve_js__WEBPACK_IMPORTED_MODULE_3__.wNAF(Point, extraOpts.endo ? Math.ceil(bits / 2) : bits);
    Point.BASE.precompute(8); // Enable precomputes. Slows down first publicKey computation by 20ms.
    return Point;
}
// Points start with byte 0x02 when y is even; otherwise 0x03
function pprefix(hasEvenY) {
    return Uint8Array.of(hasEvenY ? 0x02 : 0x03);
}
/**
 * Implementation of the Shallue and van de Woestijne method for any weierstrass curve.
 * TODO: check if there is a way to merge this with uvRatio in Edwards; move to modular.
 * b = True and y = sqrt(u / v) if (u / v) is square in F, and
 * b = False and y = sqrt(Z * (u / v)) otherwise.
 * @param Fp
 * @param Z
 * @returns
 */
function SWUFpSqrtRatio(Fp, Z) {
    // Generic implementation
    const q = Fp.ORDER;
    let l = _0n;
    for (let o = q - _1n; o % _2n === _0n; o /= _2n)
        l += _1n;
    const c1 = l; // 1. c1, the largest integer such that 2^c1 divides q - 1.
    // We need 2n ** c1 and 2n ** (c1-1). We can't use **; but we can use <<.
    // 2n ** c1 == 2n << (c1-1)
    const _2n_pow_c1_1 = _2n << (c1 - _1n - _1n);
    const _2n_pow_c1 = _2n_pow_c1_1 * _2n;
    const c2 = (q - _1n) / _2n_pow_c1; // 2. c2 = (q - 1) / (2^c1)  # Integer arithmetic
    const c3 = (c2 - _1n) / _2n; // 3. c3 = (c2 - 1) / 2            # Integer arithmetic
    const c4 = _2n_pow_c1 - _1n; // 4. c4 = 2^c1 - 1                # Integer arithmetic
    const c5 = _2n_pow_c1_1; // 5. c5 = 2^(c1 - 1)                  # Integer arithmetic
    const c6 = Fp.pow(Z, c2); // 6. c6 = Z^c2
    const c7 = Fp.pow(Z, (c2 + _1n) / _2n); // 7. c7 = Z^((c2 + 1) / 2)
    let sqrtRatio = (u, v) => {
        let tv1 = c6; // 1. tv1 = c6
        let tv2 = Fp.pow(v, c4); // 2. tv2 = v^c4
        let tv3 = Fp.sqr(tv2); // 3. tv3 = tv2^2
        tv3 = Fp.mul(tv3, v); // 4. tv3 = tv3 * v
        let tv5 = Fp.mul(u, tv3); // 5. tv5 = u * tv3
        tv5 = Fp.pow(tv5, c3); // 6. tv5 = tv5^c3
        tv5 = Fp.mul(tv5, tv2); // 7. tv5 = tv5 * tv2
        tv2 = Fp.mul(tv5, v); // 8. tv2 = tv5 * v
        tv3 = Fp.mul(tv5, u); // 9. tv3 = tv5 * u
        let tv4 = Fp.mul(tv3, tv2); // 10. tv4 = tv3 * tv2
        tv5 = Fp.pow(tv4, c5); // 11. tv5 = tv4^c5
        let isQR = Fp.eql(tv5, Fp.ONE); // 12. isQR = tv5 == 1
        tv2 = Fp.mul(tv3, c7); // 13. tv2 = tv3 * c7
        tv5 = Fp.mul(tv4, tv1); // 14. tv5 = tv4 * tv1
        tv3 = Fp.cmov(tv2, tv3, isQR); // 15. tv3 = CMOV(tv2, tv3, isQR)
        tv4 = Fp.cmov(tv5, tv4, isQR); // 16. tv4 = CMOV(tv5, tv4, isQR)
        // 17. for i in (c1, c1 - 1, ..., 2):
        for (let i = c1; i > _1n; i--) {
            let tv5 = i - _2n; // 18.    tv5 = i - 2
            tv5 = _2n << (tv5 - _1n); // 19.    tv5 = 2^tv5
            let tvv5 = Fp.pow(tv4, tv5); // 20.    tv5 = tv4^tv5
            const e1 = Fp.eql(tvv5, Fp.ONE); // 21.    e1 = tv5 == 1
            tv2 = Fp.mul(tv3, tv1); // 22.    tv2 = tv3 * tv1
            tv1 = Fp.mul(tv1, tv1); // 23.    tv1 = tv1 * tv1
            tvv5 = Fp.mul(tv4, tv1); // 24.    tv5 = tv4 * tv1
            tv3 = Fp.cmov(tv2, tv3, e1); // 25.    tv3 = CMOV(tv2, tv3, e1)
            tv4 = Fp.cmov(tvv5, tv4, e1); // 26.    tv4 = CMOV(tv5, tv4, e1)
        }
        return { isValid: isQR, value: tv3 };
    };
    if (Fp.ORDER % _4n === _3n) {
        // sqrt_ratio_3mod4(u, v)
        const c1 = (Fp.ORDER - _3n) / _4n; // 1. c1 = (q - 3) / 4     # Integer arithmetic
        const c2 = Fp.sqrt(Fp.neg(Z)); // 2. c2 = sqrt(-Z)
        sqrtRatio = (u, v) => {
            let tv1 = Fp.sqr(v); // 1. tv1 = v^2
            const tv2 = Fp.mul(u, v); // 2. tv2 = u * v
            tv1 = Fp.mul(tv1, tv2); // 3. tv1 = tv1 * tv2
            let y1 = Fp.pow(tv1, c1); // 4. y1 = tv1^c1
            y1 = Fp.mul(y1, tv2); // 5. y1 = y1 * tv2
            const y2 = Fp.mul(y1, c2); // 6. y2 = y1 * c2
            const tv3 = Fp.mul(Fp.sqr(y1), v); // 7. tv3 = y1^2; 8. tv3 = tv3 * v
            const isQR = Fp.eql(tv3, u); // 9. isQR = tv3 == u
            let y = Fp.cmov(y2, y1, isQR); // 10. y = CMOV(y2, y1, isQR)
            return { isValid: isQR, value: y }; // 11. return (isQR, y) isQR ? y : y*c2
        };
    }
    // No curves uses that
    // if (Fp.ORDER % _8n === _5n) // sqrt_ratio_5mod8
    return sqrtRatio;
}
/**
 * Simplified Shallue-van de Woestijne-Ulas Method
 * https://www.rfc-editor.org/rfc/rfc9380#section-6.6.2
 */
function mapToCurveSimpleSWU(Fp, opts) {
    validateField(Fp);
    const { A, B, Z } = opts;
    if (!Fp.isValid(A) || !Fp.isValid(B) || !Fp.isValid(Z))
        throw new Error('mapToCurveSimpleSWU: invalid opts');
    const sqrtRatio = SWUFpSqrtRatio(Fp, Z);
    if (!Fp.isOdd)
        throw new Error('Field does not have .isOdd()');
    // Input: u, an element of F.
    // Output: (x, y), a point on E.
    return (u) => {
        // prettier-ignore
        let tv1, tv2, tv3, tv4, tv5, tv6, x, y;
        tv1 = Fp.sqr(u); // 1.  tv1 = u^2
        tv1 = Fp.mul(tv1, Z); // 2.  tv1 = Z * tv1
        tv2 = Fp.sqr(tv1); // 3.  tv2 = tv1^2
        tv2 = Fp.add(tv2, tv1); // 4.  tv2 = tv2 + tv1
        tv3 = Fp.add(tv2, Fp.ONE); // 5.  tv3 = tv2 + 1
        tv3 = Fp.mul(tv3, B); // 6.  tv3 = B * tv3
        tv4 = Fp.cmov(Z, Fp.neg(tv2), !Fp.eql(tv2, Fp.ZERO)); // 7.  tv4 = CMOV(Z, -tv2, tv2 != 0)
        tv4 = Fp.mul(tv4, A); // 8.  tv4 = A * tv4
        tv2 = Fp.sqr(tv3); // 9.  tv2 = tv3^2
        tv6 = Fp.sqr(tv4); // 10. tv6 = tv4^2
        tv5 = Fp.mul(tv6, A); // 11. tv5 = A * tv6
        tv2 = Fp.add(tv2, tv5); // 12. tv2 = tv2 + tv5
        tv2 = Fp.mul(tv2, tv3); // 13. tv2 = tv2 * tv3
        tv6 = Fp.mul(tv6, tv4); // 14. tv6 = tv6 * tv4
        tv5 = Fp.mul(tv6, B); // 15. tv5 = B * tv6
        tv2 = Fp.add(tv2, tv5); // 16. tv2 = tv2 + tv5
        x = Fp.mul(tv1, tv3); // 17.   x = tv1 * tv3
        const { isValid, value } = sqrtRatio(tv2, tv6); // 18. (is_gx1_square, y1) = sqrt_ratio(tv2, tv6)
        y = Fp.mul(tv1, u); // 19.   y = tv1 * u  -> Z * u^3 * y1
        y = Fp.mul(y, value); // 20.   y = y * y1
        x = Fp.cmov(x, tv3, isValid); // 21.   x = CMOV(x, tv3, is_gx1_square)
        y = Fp.cmov(y, value, isValid); // 22.   y = CMOV(y, y1, is_gx1_square)
        const e1 = Fp.isOdd(u) === Fp.isOdd(y); // 23.  e1 = sgn0(u) == sgn0(y)
        y = Fp.cmov(Fp.neg(y), y, e1); // 24.   y = CMOV(-y, y, e1)
        const tv4_inv = FpInvertBatch(Fp, [tv4], true)[0];
        x = Fp.mul(x, tv4_inv); // 25.   x = x / tv4
        return { x, y };
    };
}
function getWLengths(Fp, Fn) {
    return {
        secretKey: Fn.BYTES,
        publicKey: 1 + Fp.BYTES,
        publicKeyUncompressed: 1 + 2 * Fp.BYTES,
        publicKeyHasPrefix: true,
        signature: 2 * Fn.BYTES,
    };
}
/**
 * Sometimes users only need getPublicKey, getSharedSecret, and secret key handling.
 * This helper ensures no signature functionality is present. Less code, smaller bundle size.
 */
function ecdh(Point, ecdhOpts = {}) {
    const { Fn } = Point;
    const randomBytes_ = ecdhOpts.randomBytes || _noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.randomBytes;
    const lengths = Object.assign(getWLengths(Point.Fp, Fn), { seed: (0,_modular_js__WEBPACK_IMPORTED_MODULE_4__.getMinHashLength)(Fn.ORDER) });
    function isValidSecretKey(secretKey) {
        try {
            return !!_normFnElement(Fn, secretKey);
        }
        catch (error) {
            return false;
        }
    }
    function isValidPublicKey(publicKey, isCompressed) {
        const { publicKey: comp, publicKeyUncompressed } = lengths;
        try {
            const l = publicKey.length;
            if (isCompressed === true && l !== comp)
                return false;
            if (isCompressed === false && l !== publicKeyUncompressed)
                return false;
            return !!Point.fromBytes(publicKey);
        }
        catch (error) {
            return false;
        }
    }
    /**
     * Produces cryptographically secure secret key from random of size
     * (groupLen + ceil(groupLen / 2)) with modulo bias being negligible.
     */
    function randomSecretKey(seed = randomBytes_(lengths.seed)) {
        return (0,_modular_js__WEBPACK_IMPORTED_MODULE_4__.mapHashToField)((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abytes2)(seed, lengths.seed, 'seed'), Fn.ORDER);
    }
    /**
     * Computes public key for a secret key. Checks for validity of the secret key.
     * @param isCompressed whether to return compact (default), or full key
     * @returns Public key, full when isCompressed=false; short when isCompressed=true
     */
    function getPublicKey(secretKey, isCompressed = true) {
        return Point.BASE.multiply(_normFnElement(Fn, secretKey)).toBytes(isCompressed);
    }
    function keygen(seed) {
        const secretKey = randomSecretKey(seed);
        return { secretKey, publicKey: getPublicKey(secretKey) };
    }
    /**
     * Quick and dirty check for item being public key. Does not validate hex, or being on-curve.
     */
    function isProbPub(item) {
        if (typeof item === 'bigint')
            return false;
        if (item instanceof Point)
            return true;
        const { secretKey, publicKey, publicKeyUncompressed } = lengths;
        if (Fn.allowedLengths || secretKey === publicKey)
            return undefined;
        const l = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('key', item).length;
        return l === publicKey || l === publicKeyUncompressed;
    }
    /**
     * ECDH (Elliptic Curve Diffie Hellman).
     * Computes shared public key from secret key A and public key B.
     * Checks: 1) secret key validity 2) shared key is on-curve.
     * Does NOT hash the result.
     * @param isCompressed whether to return compact (default), or full key
     * @returns shared public key
     */
    function getSharedSecret(secretKeyA, publicKeyB, isCompressed = true) {
        if (isProbPub(secretKeyA) === true)
            throw new Error('first arg must be private key');
        if (isProbPub(publicKeyB) === false)
            throw new Error('second arg must be public key');
        const s = _normFnElement(Fn, secretKeyA);
        const b = Point.fromHex(publicKeyB); // checks for being on-curve
        return b.multiply(s).toBytes(isCompressed);
    }
    const utils = {
        isValidSecretKey,
        isValidPublicKey,
        randomSecretKey,
        // TODO: remove
        isValidPrivateKey: isValidSecretKey,
        randomPrivateKey: randomSecretKey,
        normPrivateKeyToScalar: (key) => _normFnElement(Fn, key),
        precompute(windowSize = 8, point = Point.BASE) {
            return point.precompute(windowSize, false);
        },
    };
    return Object.freeze({ getPublicKey, getSharedSecret, keygen, Point, utils, lengths });
}
/**
 * Creates ECDSA signing interface for given elliptic curve `Point` and `hash` function.
 * We need `hash` for 2 features:
 * 1. Message prehash-ing. NOT used if `sign` / `verify` are called with `prehash: false`
 * 2. k generation in `sign`, using HMAC-drbg(hash)
 *
 * ECDSAOpts are only rarely needed.
 *
 * @example
 * ```js
 * const p256_Point = weierstrass(...);
 * const p256_sha256 = ecdsa(p256_Point, sha256);
 * const p256_sha224 = ecdsa(p256_Point, sha224);
 * const p256_sha224_r = ecdsa(p256_Point, sha224, { randomBytes: (length) => { ... } });
 * ```
 */
function ecdsa(Point, hash, ecdsaOpts = {}) {
    (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.ahash)(hash);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._validateObject)(ecdsaOpts, {}, {
        hmac: 'function',
        lowS: 'boolean',
        randomBytes: 'function',
        bits2int: 'function',
        bits2int_modN: 'function',
    });
    const randomBytes = ecdsaOpts.randomBytes || _noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.randomBytes;
    const hmac = ecdsaOpts.hmac ||
        ((key, ...msgs) => (0,_noble_hashes_hmac_js__WEBPACK_IMPORTED_MODULE_0__.hmac)(hash, key, (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(...msgs)));
    const { Fp, Fn } = Point;
    const { ORDER: CURVE_ORDER, BITS: fnBits } = Fn;
    const { keygen, getPublicKey, getSharedSecret, utils, lengths } = ecdh(Point, ecdsaOpts);
    const defaultSigOpts = {
        prehash: false,
        lowS: typeof ecdsaOpts.lowS === 'boolean' ? ecdsaOpts.lowS : false,
        format: undefined, //'compact' as ECDSASigFormat,
        extraEntropy: false,
    };
    const defaultSigOpts_format = 'compact';
    function isBiggerThanHalfOrder(number) {
        const HALF = CURVE_ORDER >> _1n;
        return number > HALF;
    }
    function validateRS(title, num) {
        if (!Fn.isValidNot0(num))
            throw new Error(`invalid signature ${title}: out of range 1..Point.Fn.ORDER`);
        return num;
    }
    function validateSigLength(bytes, format) {
        validateSigFormat(format);
        const size = lengths.signature;
        const sizer = format === 'compact' ? size : format === 'recovered' ? size + 1 : undefined;
        return (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abytes2)(bytes, sizer, `${format} signature`);
    }
    /**
     * ECDSA signature with its (r, s) properties. Supports compact, recovered & DER representations.
     */
    class Signature {
        constructor(r, s, recovery) {
            this.r = validateRS('r', r); // r in [1..N-1];
            this.s = validateRS('s', s); // s in [1..N-1];
            if (recovery != null)
                this.recovery = recovery;
            Object.freeze(this);
        }
        static fromBytes(bytes, format = defaultSigOpts_format) {
            validateSigLength(bytes, format);
            let recid;
            if (format === 'der') {
                const { r, s } = DER.toSig((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abytes2)(bytes));
                return new Signature(r, s);
            }
            if (format === 'recovered') {
                recid = bytes[0];
                format = 'compact';
                bytes = bytes.subarray(1);
            }
            const L = Fn.BYTES;
            const r = bytes.subarray(0, L);
            const s = bytes.subarray(L, L * 2);
            return new Signature(Fn.fromBytes(r), Fn.fromBytes(s), recid);
        }
        static fromHex(hex, format) {
            return this.fromBytes((0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.hexToBytes)(hex), format);
        }
        addRecoveryBit(recovery) {
            return new Signature(this.r, this.s, recovery);
        }
        recoverPublicKey(messageHash) {
            const FIELD_ORDER = Fp.ORDER;
            const { r, s, recovery: rec } = this;
            if (rec == null || ![0, 1, 2, 3].includes(rec))
                throw new Error('recovery id invalid');
            // ECDSA recovery is hard for cofactor > 1 curves.
            // In sign, `r = q.x mod n`, and here we recover q.x from r.
            // While recovering q.x >= n, we need to add r+n for cofactor=1 curves.
            // However, for cofactor>1, r+n may not get q.x:
            // r+n*i would need to be done instead where i is unknown.
            // To easily get i, we either need to:
            // a. increase amount of valid recid values (4, 5...); OR
            // b. prohibit non-prime-order signatures (recid > 1).
            const hasCofactor = CURVE_ORDER * _2n < FIELD_ORDER;
            if (hasCofactor && rec > 1)
                throw new Error('recovery id is ambiguous for h>1 curve');
            const radj = rec === 2 || rec === 3 ? r + CURVE_ORDER : r;
            if (!Fp.isValid(radj))
                throw new Error('recovery id 2 or 3 invalid');
            const x = Fp.toBytes(radj);
            const R = Point.fromBytes((0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(pprefix((rec & 1) === 0), x));
            const ir = Fn.inv(radj); // r^-1
            const h = bits2int_modN((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('msgHash', messageHash)); // Truncate hash
            const u1 = Fn.create(-h * ir); // -hr^-1
            const u2 = Fn.create(s * ir); // sr^-1
            // (sr^-1)R-(hr^-1)G = -(hr^-1)G + (sr^-1). unsafe is fine: there is no private data.
            const Q = Point.BASE.multiplyUnsafe(u1).add(R.multiplyUnsafe(u2));
            if (Q.is0())
                throw new Error('point at infinify');
            Q.assertValidity();
            return Q;
        }
        // Signatures should be low-s, to prevent malleability.
        hasHighS() {
            return isBiggerThanHalfOrder(this.s);
        }
        toBytes(format = defaultSigOpts_format) {
            validateSigFormat(format);
            if (format === 'der')
                return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.hexToBytes)(DER.hexFromSig(this));
            const r = Fn.toBytes(this.r);
            const s = Fn.toBytes(this.s);
            if (format === 'recovered') {
                if (this.recovery == null)
                    throw new Error('recovery bit must be present');
                return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(Uint8Array.of(this.recovery), r, s);
            }
            return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(r, s);
        }
        toHex(format) {
            return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.bytesToHex)(this.toBytes(format));
        }
        // TODO: remove
        assertValidity() { }
        static fromCompact(hex) {
            return Signature.fromBytes((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('sig', hex), 'compact');
        }
        static fromDER(hex) {
            return Signature.fromBytes((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('sig', hex), 'der');
        }
        normalizeS() {
            return this.hasHighS() ? new Signature(this.r, Fn.neg(this.s), this.recovery) : this;
        }
        toDERRawBytes() {
            return this.toBytes('der');
        }
        toDERHex() {
            return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.bytesToHex)(this.toBytes('der'));
        }
        toCompactRawBytes() {
            return this.toBytes('compact');
        }
        toCompactHex() {
            return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.bytesToHex)(this.toBytes('compact'));
        }
    }
    // RFC6979: ensure ECDSA msg is X bytes and < N. RFC suggests optional truncating via bits2octets.
    // FIPS 186-4 4.6 suggests the leftmost min(nBitLen, outLen) bits, which matches bits2int.
    // bits2int can produce res>N, we can do mod(res, N) since the bitLen is the same.
    // int2octets can't be used; pads small msgs with 0: unacceptatble for trunc as per RFC vectors
    const bits2int = ecdsaOpts.bits2int ||
        function bits2int_def(bytes) {
            // Our custom check "just in case", for protection against DoS
            if (bytes.length > 8192)
                throw new Error('input is too large');
            // For curves with nBitLength % 8 !== 0: bits2octets(bits2octets(m)) !== bits2octets(m)
            // for some cases, since bytes.length * 8 is not actual bitLength.
            const num = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.bytesToNumberBE)(bytes); // check for == u8 done here
            const delta = bytes.length * 8 - fnBits; // truncate to nBitLength leftmost bits
            return delta > 0 ? num >> BigInt(delta) : num;
        };
    const bits2int_modN = ecdsaOpts.bits2int_modN ||
        function bits2int_modN_def(bytes) {
            return Fn.create(bits2int(bytes)); // can't use bytesToNumberBE here
        };
    // Pads output with zero as per spec
    const ORDER_MASK = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.bitMask)(fnBits);
    /** Converts to bytes. Checks if num in `[0..ORDER_MASK-1]` e.g.: `[0..2^256-1]`. */
    function int2octets(num) {
        // IMPORTANT: the check ensures working for case `Fn.BYTES != Fn.BITS * 8`
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.aInRange)('num < 2^' + fnBits, num, _0n, ORDER_MASK);
        return Fn.toBytes(num);
    }
    function validateMsgAndHash(message, prehash) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abytes2)(message, undefined, 'message');
        return prehash ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__._abytes2)(hash(message), undefined, 'prehashed message') : message;
    }
    /**
     * Steps A, D of RFC6979 3.2.
     * Creates RFC6979 seed; converts msg/privKey to numbers.
     * Used only in sign, not in verify.
     *
     * Warning: we cannot assume here that message has same amount of bytes as curve order,
     * this will be invalid at least for P521. Also it can be bigger for P224 + SHA256.
     */
    function prepSig(message, privateKey, opts) {
        if (['recovered', 'canonical'].some((k) => k in opts))
            throw new Error('sign() legacy options not supported');
        const { lowS, prehash, extraEntropy } = validateSigOpts(opts, defaultSigOpts);
        message = validateMsgAndHash(message, prehash); // RFC6979 3.2 A: h1 = H(m)
        // We can't later call bits2octets, since nested bits2int is broken for curves
        // with fnBits % 8 !== 0. Because of that, we unwrap it here as int2octets call.
        // const bits2octets = (bits) => int2octets(bits2int_modN(bits))
        const h1int = bits2int_modN(message);
        const d = _normFnElement(Fn, privateKey); // validate secret key, convert to bigint
        const seedArgs = [int2octets(d), int2octets(h1int)];
        // extraEntropy. RFC6979 3.6: additional k' (optional).
        if (extraEntropy != null && extraEntropy !== false) {
            // K = HMAC_K(V || 0x00 || int2octets(x) || bits2octets(h1) || k')
            // gen random bytes OR pass as-is
            const e = extraEntropy === true ? randomBytes(lengths.secretKey) : extraEntropy;
            seedArgs.push((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('extraEntropy', e)); // check for being bytes
        }
        const seed = (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(...seedArgs); // Step D of RFC6979 3.2
        const m = h1int; // NOTE: no need to call bits2int second time here, it is inside truncateHash!
        // Converts signature params into point w r/s, checks result for validity.
        // To transform k => Signature:
        // q = k⋅G
        // r = q.x mod n
        // s = k^-1(m + rd) mod n
        // Can use scalar blinding b^-1(bm + bdr) where b ∈ [1,q−1] according to
        // https://tches.iacr.org/index.php/TCHES/article/view/7337/6509. We've decided against it:
        // a) dependency on CSPRNG b) 15% slowdown c) doesn't really help since bigints are not CT
        function k2sig(kBytes) {
            // RFC 6979 Section 3.2, step 3: k = bits2int(T)
            // Important: all mod() calls here must be done over N
            const k = bits2int(kBytes); // mod n, not mod p
            if (!Fn.isValidNot0(k))
                return; // Valid scalars (including k) must be in 1..N-1
            const ik = Fn.inv(k); // k^-1 mod n
            const q = Point.BASE.multiply(k).toAffine(); // q = k⋅G
            const r = Fn.create(q.x); // r = q.x mod n
            if (r === _0n)
                return;
            const s = Fn.create(ik * Fn.create(m + r * d)); // Not using blinding here, see comment above
            if (s === _0n)
                return;
            let recovery = (q.x === r ? 0 : 2) | Number(q.y & _1n); // recovery bit (2 or 3, when q.x > n)
            let normS = s;
            if (lowS && isBiggerThanHalfOrder(s)) {
                normS = Fn.neg(s); // if lowS was passed, ensure s is always
                recovery ^= 1; // // in the bottom half of N
            }
            return new Signature(r, normS, recovery); // use normS, not s
        }
        return { seed, k2sig };
    }
    /**
     * Signs message hash with a secret key.
     *
     * ```
     * sign(m, d) where
     *   k = rfc6979_hmac_drbg(m, d)
     *   (x, y) = G × k
     *   r = x mod n
     *   s = (m + dr) / k mod n
     * ```
     */
    function sign(message, secretKey, opts = {}) {
        message = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('message', message);
        const { seed, k2sig } = prepSig(message, secretKey, opts); // Steps A, D of RFC6979 3.2.
        const drbg = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.createHmacDrbg)(hash.outputLen, Fn.BYTES, hmac);
        const sig = drbg(seed, k2sig); // Steps B, C, D, E, F, G
        return sig;
    }
    function tryParsingSig(sg) {
        // Try to deduce format
        let sig = undefined;
        const isHex = typeof sg === 'string' || (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.isBytes)(sg);
        const isObj = !isHex &&
            sg !== null &&
            typeof sg === 'object' &&
            typeof sg.r === 'bigint' &&
            typeof sg.s === 'bigint';
        if (!isHex && !isObj)
            throw new Error('invalid signature, expected Uint8Array, hex string or Signature instance');
        if (isObj) {
            sig = new Signature(sg.r, sg.s);
        }
        else if (isHex) {
            try {
                sig = Signature.fromBytes((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('sig', sg), 'der');
            }
            catch (derError) {
                if (!(derError instanceof DER.Err))
                    throw derError;
            }
            if (!sig) {
                try {
                    sig = Signature.fromBytes((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('sig', sg), 'compact');
                }
                catch (error) {
                    return false;
                }
            }
        }
        if (!sig)
            return false;
        return sig;
    }
    /**
     * Verifies a signature against message and public key.
     * Rejects lowS signatures by default: see {@link ECDSAVerifyOpts}.
     * Implements section 4.1.4 from https://www.secg.org/sec1-v2.pdf:
     *
     * ```
     * verify(r, s, h, P) where
     *   u1 = hs^-1 mod n
     *   u2 = rs^-1 mod n
     *   R = u1⋅G + u2⋅P
     *   mod(R.x, n) == r
     * ```
     */
    function verify(signature, message, publicKey, opts = {}) {
        const { lowS, prehash, format } = validateSigOpts(opts, defaultSigOpts);
        publicKey = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('publicKey', publicKey);
        message = validateMsgAndHash((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('message', message), prehash);
        if ('strict' in opts)
            throw new Error('options.strict was renamed to lowS');
        const sig = format === undefined
            ? tryParsingSig(signature)
            : Signature.fromBytes((0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.ensureBytes)('sig', signature), format);
        if (sig === false)
            return false;
        try {
            const P = Point.fromBytes(publicKey);
            if (lowS && sig.hasHighS())
                return false;
            const { r, s } = sig;
            const h = bits2int_modN(message); // mod n, not mod p
            const is = Fn.inv(s); // s^-1 mod n
            const u1 = Fn.create(h * is); // u1 = hs^-1 mod n
            const u2 = Fn.create(r * is); // u2 = rs^-1 mod n
            const R = Point.BASE.multiplyUnsafe(u1).add(P.multiplyUnsafe(u2)); // u1⋅G + u2⋅P
            if (R.is0())
                return false;
            const v = Fn.create(R.x); // v = r.x mod n
            return v === r;
        }
        catch (e) {
            return false;
        }
    }
    function recoverPublicKey(signature, message, opts = {}) {
        const { prehash } = validateSigOpts(opts, defaultSigOpts);
        message = validateMsgAndHash(message, prehash);
        return Signature.fromBytes(signature, 'recovered').recoverPublicKey(message).toBytes();
    }
    return Object.freeze({
        keygen,
        getPublicKey,
        getSharedSecret,
        utils,
        lengths,
        Point,
        sign,
        verify,
        recoverPublicKey,
        Signature,
        hash,
    });
}
/** @deprecated use `weierstrass` in newer releases */
function weierstrassPoints(c) {
    const { CURVE, curveOpts } = _weierstrass_legacy_opts_to_new(c);
    const Point = weierstrassN(CURVE, curveOpts);
    return _weierstrass_new_output_to_legacy(c, Point);
}
function _weierstrass_legacy_opts_to_new(c) {
    const CURVE = {
        a: c.a,
        b: c.b,
        p: c.Fp.ORDER,
        n: c.n,
        h: c.h,
        Gx: c.Gx,
        Gy: c.Gy,
    };
    const Fp = c.Fp;
    let allowedLengths = c.allowedPrivateKeyLengths
        ? Array.from(new Set(c.allowedPrivateKeyLengths.map((l) => Math.ceil(l / 2))))
        : undefined;
    const Fn = (0,_modular_js__WEBPACK_IMPORTED_MODULE_4__.Field)(CURVE.n, {
        BITS: c.nBitLength,
        allowedLengths: allowedLengths,
        modFromBytes: c.wrapPrivateKey,
    });
    const curveOpts = {
        Fp,
        Fn,
        allowInfinityPoint: c.allowInfinityPoint,
        endo: c.endo,
        isTorsionFree: c.isTorsionFree,
        clearCofactor: c.clearCofactor,
        fromBytes: c.fromBytes,
        toBytes: c.toBytes,
    };
    return { CURVE, curveOpts };
}
function _ecdsa_legacy_opts_to_new(c) {
    const { CURVE, curveOpts } = _weierstrass_legacy_opts_to_new(c);
    const ecdsaOpts = {
        hmac: c.hmac,
        randomBytes: c.randomBytes,
        lowS: c.lowS,
        bits2int: c.bits2int,
        bits2int_modN: c.bits2int_modN,
    };
    return { CURVE, curveOpts, hash: c.hash, ecdsaOpts };
}
function _legacyHelperEquat(Fp, a, b) {
    /**
     * y² = x³ + ax + b: Short weierstrass curve formula. Takes x, returns y².
     * @returns y²
     */
    function weierstrassEquation(x) {
        const x2 = Fp.sqr(x); // x * x
        const x3 = Fp.mul(x2, x); // x² * x
        return Fp.add(Fp.add(x3, Fp.mul(x, a)), b); // x³ + a * x + b
    }
    return weierstrassEquation;
}
function _weierstrass_new_output_to_legacy(c, Point) {
    const { Fp, Fn } = Point;
    function isWithinCurveOrder(num) {
        return inRange(num, _1n, Fn.ORDER);
    }
    const weierstrassEquation = _legacyHelperEquat(Fp, c.a, c.b);
    return Object.assign({}, {
        CURVE: c,
        Point: Point,
        ProjectivePoint: Point,
        normPrivateKeyToScalar: (key) => _normFnElement(Fn, key),
        weierstrassEquation,
        isWithinCurveOrder,
    });
}
function _ecdsa_new_output_to_legacy(c, _ecdsa) {
    const Point = _ecdsa.Point;
    return Object.assign({}, _ecdsa, {
        ProjectivePoint: Point,
        CURVE: Object.assign({}, c, (0,_modular_js__WEBPACK_IMPORTED_MODULE_4__.nLength)(Point.Fn.ORDER, Point.Fn.BITS)),
    });
}
// _ecdsa_legacy
function weierstrass(c) {
    const { CURVE, curveOpts, hash, ecdsaOpts } = _ecdsa_legacy_opts_to_new(c);
    const Point = weierstrassN(CURVE, curveOpts);
    const signs = ecdsa(Point, hash, ecdsaOpts);
    return _ecdsa_new_output_to_legacy(c, signs);
}
//# sourceMappingURL=weierstrass.js.map

/***/ },

/***/ 329
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   schnorr: () => (/* binding */ schnorr),
/* harmony export */   secp256k1: () => (/* binding */ secp256k1)
/* harmony export */ });
/* unused harmony exports secp256k1_hasher, hashToCurve, encodeToCurve */
/* unused harmony import specifier */ var sha256;
/* unused harmony import specifier */ var isogenyMap;
/* unused harmony import specifier */ var createHasher;
/* unused harmony import specifier */ var mapToCurveSimpleSWU;
/* harmony import */ var _noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(429);
/* harmony import */ var _noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(976);
/* harmony import */ var _shortw_utils_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(333);
/* harmony import */ var _abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(30);
/* harmony import */ var _abstract_weierstrass_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(316);
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(848);
/**
 * SECG secp256k1. See [pdf](https://www.secg.org/sec2-v2.pdf).
 *
 * Belongs to Koblitz curves: it has efficiently-computable GLV endomorphism ψ,
 * check out {@link EndomorphismOpts}. Seems to be rigid (not backdoored).
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */







// Seems like generator was produced from some seed:
// `Point.BASE.multiply(Point.Fn.inv(2n, N)).toAffine().x`
// // gives short x 0x3b78ce563f89a0ed9414f5aa28ad0d96d6795f9c63n
const secp256k1_CURVE = {
    p: BigInt('0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f'),
    n: BigInt('0xfffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141'),
    h: BigInt(1),
    a: BigInt(0),
    b: BigInt(7),
    Gx: BigInt('0x79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798'),
    Gy: BigInt('0x483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8'),
};
const secp256k1_ENDO = {
    beta: BigInt('0x7ae96a2b657c07106e64479eac3434e99cf0497512f58995c1396c28719501ee'),
    basises: [
        [BigInt('0x3086d221a7d46bcde86c90e49284eb15'), -BigInt('0xe4437ed6010e88286f547fa90abfe4c3')],
        [BigInt('0x114ca50f7a8e2f3f657c1108d9d44cfd8'), BigInt('0x3086d221a7d46bcde86c90e49284eb15')],
    ],
};
const _0n = /* @__PURE__ */ BigInt(0);
const _1n = /* @__PURE__ */ BigInt(1);
const _2n = /* @__PURE__ */ BigInt(2);
/**
 * √n = n^((p+1)/4) for fields p = 3 mod 4. We unwrap the loop and multiply bit-by-bit.
 * (P+1n/4n).toString(2) would produce bits [223x 1, 0, 22x 1, 4x 0, 11, 00]
 */
function sqrtMod(y) {
    const P = secp256k1_CURVE.p;
    // prettier-ignore
    const _3n = BigInt(3), _6n = BigInt(6), _11n = BigInt(11), _22n = BigInt(22);
    // prettier-ignore
    const _23n = BigInt(23), _44n = BigInt(44), _88n = BigInt(88);
    const b2 = (y * y * y) % P; // x^3, 11
    const b3 = (b2 * b2 * y) % P; // x^7
    const b6 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b3, _3n, P) * b3) % P;
    const b9 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b6, _3n, P) * b3) % P;
    const b11 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b9, _2n, P) * b2) % P;
    const b22 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b11, _11n, P) * b11) % P;
    const b44 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b22, _22n, P) * b22) % P;
    const b88 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b44, _44n, P) * b44) % P;
    const b176 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b88, _88n, P) * b88) % P;
    const b220 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b176, _44n, P) * b44) % P;
    const b223 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b220, _3n, P) * b3) % P;
    const t1 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(b223, _23n, P) * b22) % P;
    const t2 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(t1, _6n, P) * b2) % P;
    const root = (0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.pow2)(t2, _2n, P);
    if (!Fpk1.eql(Fpk1.sqr(root), y))
        throw new Error('Cannot find square root');
    return root;
}
const Fpk1 = (0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.Field)(secp256k1_CURVE.p, { sqrt: sqrtMod });
/**
 * secp256k1 curve, ECDSA and ECDH methods.
 *
 * Field: `2n**256n - 2n**32n - 2n**9n - 2n**8n - 2n**7n - 2n**6n - 2n**4n - 1n`
 *
 * @example
 * ```js
 * import { secp256k1 } from '@noble/curves/secp256k1';
 * const { secretKey, publicKey } = secp256k1.keygen();
 * const msg = new TextEncoder().encode('hello');
 * const sig = secp256k1.sign(msg, secretKey);
 * const isValid = secp256k1.verify(sig, msg, publicKey) === true;
 * ```
 */
const secp256k1 = (0,_shortw_utils_js__WEBPACK_IMPORTED_MODULE_2__.createCurve)({ ...secp256k1_CURVE, Fp: Fpk1, lowS: true, endo: secp256k1_ENDO }, _noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_0__.sha256);
// Schnorr signatures are superior to ECDSA from above. Below is Schnorr-specific BIP0340 code.
// https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki
/** An object mapping tags to their tagged hash prefix of [SHA256(tag) | SHA256(tag)] */
const TAGGED_HASH_PREFIXES = {};
function taggedHash(tag, ...messages) {
    let tagP = TAGGED_HASH_PREFIXES[tag];
    if (tagP === undefined) {
        const tagH = (0,_noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_0__.sha256)((0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_1__.utf8ToBytes)(tag));
        tagP = (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(tagH, tagH);
        TAGGED_HASH_PREFIXES[tag] = tagP;
    }
    return (0,_noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_0__.sha256)((0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(tagP, ...messages));
}
// ECDSA compact points are 33-byte. Schnorr is 32: we strip first byte 0x02 or 0x03
const pointToBytes = (point) => point.toBytes(true).slice(1);
const Pointk1 = /* @__PURE__ */ (() => secp256k1.Point)();
const hasEven = (y) => y % _2n === _0n;
// Calculate point, scalar and bytes
function schnorrGetExtPubKey(priv) {
    const { Fn, BASE } = Pointk1;
    const d_ = (0,_abstract_weierstrass_js__WEBPACK_IMPORTED_MODULE_4__._normFnElement)(Fn, priv);
    const p = BASE.multiply(d_); // P = d'⋅G; 0 < d' < n check is done inside
    const scalar = hasEven(p.y) ? d_ : Fn.neg(d_);
    return { scalar, bytes: pointToBytes(p) };
}
/**
 * lift_x from BIP340. Convert 32-byte x coordinate to elliptic curve point.
 * @returns valid point checked for being on-curve
 */
function lift_x(x) {
    const Fp = Fpk1;
    if (!Fp.isValidNot0(x))
        throw new Error('invalid x: Fail if x ≥ p');
    const xx = Fp.create(x * x);
    const c = Fp.create(xx * x + BigInt(7)); // Let c = x³ + 7 mod p.
    let y = Fp.sqrt(c); // Let y = c^(p+1)/4 mod p. Same as sqrt().
    // Return the unique point P such that x(P) = x and
    // y(P) = y if y mod 2 = 0 or y(P) = p-y otherwise.
    if (!hasEven(y))
        y = Fp.neg(y);
    const p = Pointk1.fromAffine({ x, y });
    p.assertValidity();
    return p;
}
const num = _utils_js__WEBPACK_IMPORTED_MODULE_5__.bytesToNumberBE;
/**
 * Create tagged hash, convert it to bigint, reduce modulo-n.
 */
function challenge(...args) {
    return Pointk1.Fn.create(num(taggedHash('BIP0340/challenge', ...args)));
}
/**
 * Schnorr public key is just `x` coordinate of Point as per BIP340.
 */
function schnorrGetPublicKey(secretKey) {
    return schnorrGetExtPubKey(secretKey).bytes; // d'=int(sk). Fail if d'=0 or d'≥n. Ret bytes(d'⋅G)
}
/**
 * Creates Schnorr signature as per BIP340. Verifies itself before returning anything.
 * auxRand is optional and is not the sole source of k generation: bad CSPRNG won't be dangerous.
 */
function schnorrSign(message, secretKey, auxRand = (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_1__.randomBytes)(32)) {
    const { Fn } = Pointk1;
    const m = (0,_utils_js__WEBPACK_IMPORTED_MODULE_5__.ensureBytes)('message', message);
    const { bytes: px, scalar: d } = schnorrGetExtPubKey(secretKey); // checks for isWithinCurveOrder
    const a = (0,_utils_js__WEBPACK_IMPORTED_MODULE_5__.ensureBytes)('auxRand', auxRand, 32); // Auxiliary random data a: a 32-byte array
    const t = Fn.toBytes(d ^ num(taggedHash('BIP0340/aux', a))); // Let t be the byte-wise xor of bytes(d) and hash/aux(a)
    const rand = taggedHash('BIP0340/nonce', t, px, m); // Let rand = hash/nonce(t || bytes(P) || m)
    // Let k' = int(rand) mod n. Fail if k' = 0. Let R = k'⋅G
    const { bytes: rx, scalar: k } = schnorrGetExtPubKey(rand);
    const e = challenge(rx, px, m); // Let e = int(hash/challenge(bytes(R) || bytes(P) || m)) mod n.
    const sig = new Uint8Array(64); // Let sig = bytes(R) || bytes((k + ed) mod n).
    sig.set(rx, 0);
    sig.set(Fn.toBytes(Fn.create(k + e * d)), 32);
    // If Verify(bytes(P), m, sig) (see below) returns failure, abort
    if (!schnorrVerify(sig, m, px))
        throw new Error('sign: Invalid signature produced');
    return sig;
}
/**
 * Verifies Schnorr signature.
 * Will swallow errors & return false except for initial type validation of arguments.
 */
function schnorrVerify(signature, message, publicKey) {
    const { Fn, BASE } = Pointk1;
    const sig = (0,_utils_js__WEBPACK_IMPORTED_MODULE_5__.ensureBytes)('signature', signature, 64);
    const m = (0,_utils_js__WEBPACK_IMPORTED_MODULE_5__.ensureBytes)('message', message);
    const pub = (0,_utils_js__WEBPACK_IMPORTED_MODULE_5__.ensureBytes)('publicKey', publicKey, 32);
    try {
        const P = lift_x(num(pub)); // P = lift_x(int(pk)); fail if that fails
        const r = num(sig.subarray(0, 32)); // Let r = int(sig[0:32]); fail if r ≥ p.
        if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_5__.inRange)(r, _1n, secp256k1_CURVE.p))
            return false;
        const s = num(sig.subarray(32, 64)); // Let s = int(sig[32:64]); fail if s ≥ n.
        if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_5__.inRange)(s, _1n, secp256k1_CURVE.n))
            return false;
        // int(challenge(bytes(r)||bytes(P)||m))%n
        const e = challenge(Fn.toBytes(r), pointToBytes(P), m);
        // R = s⋅G - e⋅P, where -eP == (n-e)P
        const R = BASE.multiplyUnsafe(s).add(P.multiplyUnsafe(Fn.neg(e)));
        const { x, y } = R.toAffine();
        // Fail if is_infinite(R) / not has_even_y(R) / x(R) ≠ r.
        if (R.is0() || !hasEven(y) || x !== r)
            return false;
        return true;
    }
    catch (error) {
        return false;
    }
}
/**
 * Schnorr signatures over secp256k1.
 * https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki
 * @example
 * ```js
 * import { schnorr } from '@noble/curves/secp256k1';
 * const { secretKey, publicKey } = schnorr.keygen();
 * // const publicKey = schnorr.getPublicKey(secretKey);
 * const msg = new TextEncoder().encode('hello');
 * const sig = schnorr.sign(msg, secretKey);
 * const isValid = schnorr.verify(sig, msg, publicKey);
 * ```
 */
const schnorr = /* @__PURE__ */ (() => {
    const size = 32;
    const seedLength = 48;
    const randomSecretKey = (seed = (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_1__.randomBytes)(seedLength)) => {
        return (0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.mapHashToField)(seed, secp256k1_CURVE.n);
    };
    // TODO: remove
    secp256k1.utils.randomSecretKey;
    function keygen(seed) {
        const secretKey = randomSecretKey(seed);
        return { secretKey, publicKey: schnorrGetPublicKey(secretKey) };
    }
    return {
        keygen,
        getPublicKey: schnorrGetPublicKey,
        sign: schnorrSign,
        verify: schnorrVerify,
        Point: Pointk1,
        utils: {
            randomSecretKey: randomSecretKey,
            randomPrivateKey: randomSecretKey,
            taggedHash,
            // TODO: remove
            lift_x,
            pointToBytes,
            numberToBytesBE: _utils_js__WEBPACK_IMPORTED_MODULE_5__.numberToBytesBE,
            bytesToNumberBE: _utils_js__WEBPACK_IMPORTED_MODULE_5__.bytesToNumberBE,
            mod: _abstract_modular_js__WEBPACK_IMPORTED_MODULE_3__.mod,
        },
        lengths: {
            secretKey: size,
            publicKey: size,
            publicKeyHasPrefix: false,
            signature: size * 2,
            seed: seedLength,
        },
    };
})();
const isoMap = /* @__PURE__ */ (/* unused pure expression or super */ null && ((() => isogenyMap(Fpk1, [
    // xNum
    [
        '0x8e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38daaaaa8c7',
        '0x7d3d4c80bc321d5b9f315cea7fd44c5d595d2fc0bf63b92dfff1044f17c6581',
        '0x534c328d23f234e6e2a413deca25caece4506144037c40314ecbd0b53d9dd262',
        '0x8e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38daaaaa88c',
    ],
    // xDen
    [
        '0xd35771193d94918a9ca34ccbb7b640dd86cd409542f8487d9fe6b745781eb49b',
        '0xedadc6f64383dc1df7c4b2d51b54225406d36b641f5e41bbc52a56612a8c6d14',
        '0x0000000000000000000000000000000000000000000000000000000000000001', // LAST 1
    ],
    // yNum
    [
        '0x4bda12f684bda12f684bda12f684bda12f684bda12f684bda12f684b8e38e23c',
        '0xc75e0c32d5cb7c0fa9d0a54b12a0a6d5647ab046d686da6fdffc90fc201d71a3',
        '0x29a6194691f91a73715209ef6512e576722830a201be2018a765e85a9ecee931',
        '0x2f684bda12f684bda12f684bda12f684bda12f684bda12f684bda12f38e38d84',
    ],
    // yDen
    [
        '0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffefffff93b',
        '0x7a06534bb8bdb49fd5e9e6632722c2989467c1bfc8e8d978dfb425d2685c2573',
        '0x6484aa716545ca2cf3a70c3fa8fe337e0a3d21162f0d6299a7bf8192bfd2a76f',
        '0x0000000000000000000000000000000000000000000000000000000000000001', // LAST 1
    ],
].map((i) => i.map((j) => BigInt(j)))))()));
const mapSWU = /* @__PURE__ */ (/* unused pure expression or super */ null && ((() => mapToCurveSimpleSWU(Fpk1, {
    A: BigInt('0x3f8731abdd661adca08a5558f0f5d272e953d363cb6f0e5d405447c01a444533'),
    B: BigInt('1771'),
    Z: Fpk1.create(BigInt('-11')),
}))()));
/** Hashing / encoding to secp256k1 points / field. RFC 9380 methods. */
const secp256k1_hasher = /* @__PURE__ */ (/* unused pure expression or super */ null && ((() => createHasher(secp256k1.Point, (scalars) => {
    const { x, y } = mapSWU(Fpk1.create(scalars[0]));
    return isoMap(x, y);
}, {
    DST: 'secp256k1_XMD:SHA-256_SSWU_RO_',
    encodeDST: 'secp256k1_XMD:SHA-256_SSWU_NU_',
    p: Fpk1.ORDER,
    m: 1,
    k: 128,
    expand: 'xmd',
    hash: sha256,
}))()));
/** @deprecated use `import { secp256k1_hasher } from '@noble/curves/secp256k1.js';` */
const hashToCurve = /* @__PURE__ */ (/* unused pure expression or super */ null && ((() => secp256k1_hasher.hashToCurve)()));
/** @deprecated use `import { secp256k1_hasher } from '@noble/curves/secp256k1.js';` */
const encodeToCurve = /* @__PURE__ */ (/* unused pure expression or super */ null && ((() => secp256k1_hasher.encodeToCurve)()));
//# sourceMappingURL=secp256k1.js.map

/***/ },

/***/ 848
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   _abool2: () => (/* binding */ _abool2),
/* harmony export */   _abytes2: () => (/* binding */ _abytes2),
/* harmony export */   _validateObject: () => (/* binding */ _validateObject),
/* harmony export */   aInRange: () => (/* binding */ aInRange),
/* harmony export */   bitLen: () => (/* binding */ bitLen),
/* harmony export */   bitMask: () => (/* binding */ bitMask),
/* harmony export */   bytesToNumberBE: () => (/* binding */ bytesToNumberBE),
/* harmony export */   bytesToNumberLE: () => (/* binding */ bytesToNumberLE),
/* harmony export */   createHmacDrbg: () => (/* binding */ createHmacDrbg),
/* harmony export */   ensureBytes: () => (/* binding */ ensureBytes),
/* harmony export */   inRange: () => (/* binding */ inRange),
/* harmony export */   memoized: () => (/* binding */ memoized),
/* harmony export */   numberToBytesBE: () => (/* binding */ numberToBytesBE),
/* harmony export */   numberToBytesLE: () => (/* binding */ numberToBytesLE),
/* harmony export */   numberToHexUnpadded: () => (/* binding */ numberToHexUnpadded)
/* harmony export */ });
/* unused harmony exports abool, hexToNumber, numberToVarBytesBE, equalBytes, copyBytes, asciiToBytes, bitGet, bitSet, validateObject, isHash, notImplemented */
/* unused harmony import specifier */ var hexToBytes_;
/* harmony import */ var _noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(976);
/**
 * Hex, bytes and number utilities.
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */


const _0n = /* @__PURE__ */ BigInt(0);
const _1n = /* @__PURE__ */ BigInt(1);
function abool(title, value) {
    if (typeof value !== 'boolean')
        throw new Error(title + ' boolean expected, got ' + value);
}
// tmp name until v2
function _abool2(value, title = '') {
    if (typeof value !== 'boolean') {
        const prefix = title && `"${title}"`;
        throw new Error(prefix + 'expected boolean, got type=' + typeof value);
    }
    return value;
}
// tmp name until v2
/** Asserts something is Uint8Array. */
function _abytes2(value, length, title = '') {
    const bytes = (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__.isBytes)(value);
    const len = value?.length;
    const needsLen = length !== undefined;
    if (!bytes || (needsLen && len !== length)) {
        const prefix = title && `"${title}" `;
        const ofLen = needsLen ? ` of length ${length}` : '';
        const got = bytes ? `length=${len}` : `type=${typeof value}`;
        throw new Error(prefix + 'expected Uint8Array' + ofLen + ', got ' + got);
    }
    return value;
}
// Used in weierstrass, der
function numberToHexUnpadded(num) {
    const hex = num.toString(16);
    return hex.length & 1 ? '0' + hex : hex;
}
function hexToNumber(hex) {
    if (typeof hex !== 'string')
        throw new Error('hex string expected, got ' + typeof hex);
    return hex === '' ? _0n : BigInt('0x' + hex); // Big Endian
}
// BE: Big Endian, LE: Little Endian
function bytesToNumberBE(bytes) {
    return hexToNumber((0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToHex)(bytes));
}
function bytesToNumberLE(bytes) {
    (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(bytes);
    return hexToNumber((0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToHex)(Uint8Array.from(bytes).reverse()));
}
function numberToBytesBE(n, len) {
    return (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__.hexToBytes)(n.toString(16).padStart(len * 2, '0'));
}
function numberToBytesLE(n, len) {
    return numberToBytesBE(n, len).reverse();
}
// Unpadded, rarely used
function numberToVarBytesBE(n) {
    return hexToBytes_(numberToHexUnpadded(n));
}
/**
 * Takes hex string or Uint8Array, converts to Uint8Array.
 * Validates output length.
 * Will throw error for other types.
 * @param title descriptive title for an error e.g. 'secret key'
 * @param hex hex string or Uint8Array
 * @param expectedLength optional, will compare to result array's length
 * @returns
 */
function ensureBytes(title, hex, expectedLength) {
    let res;
    if (typeof hex === 'string') {
        try {
            res = (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__.hexToBytes)(hex);
        }
        catch (e) {
            throw new Error(title + ' must be hex string or Uint8Array, cause: ' + e);
        }
    }
    else if ((0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__.isBytes)(hex)) {
        // Uint8Array.from() instead of hash.slice() because node.js Buffer
        // is instance of Uint8Array, and its slice() creates **mutable** copy
        res = Uint8Array.from(hex);
    }
    else {
        throw new Error(title + ' must be hex string or Uint8Array');
    }
    const len = res.length;
    if (typeof expectedLength === 'number' && len !== expectedLength)
        throw new Error(title + ' of length ' + expectedLength + ' expected, got ' + len);
    return res;
}
// Compares 2 u8a-s in kinda constant time
function equalBytes(a, b) {
    if (a.length !== b.length)
        return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++)
        diff |= a[i] ^ b[i];
    return diff === 0;
}
/**
 * Copies Uint8Array. We can't use u8a.slice(), because u8a can be Buffer,
 * and Buffer#slice creates mutable copy. Never use Buffers!
 */
function copyBytes(bytes) {
    return Uint8Array.from(bytes);
}
/**
 * Decodes 7-bit ASCII string to Uint8Array, throws on non-ascii symbols
 * Should be safe to use for things expected to be ASCII.
 * Returns exact same result as utf8ToBytes for ASCII or throws.
 */
function asciiToBytes(ascii) {
    return Uint8Array.from(ascii, (c, i) => {
        const charCode = c.charCodeAt(0);
        if (c.length !== 1 || charCode > 127) {
            throw new Error(`string contains non-ASCII character "${ascii[i]}" with code ${charCode} at position ${i}`);
        }
        return charCode;
    });
}
/**
 * @example utf8ToBytes('abc') // new Uint8Array([97, 98, 99])
 */
// export const utf8ToBytes: typeof utf8ToBytes_ = utf8ToBytes_;
/**
 * Converts bytes to string using UTF8 encoding.
 * @example bytesToUtf8(Uint8Array.from([97, 98, 99])) // 'abc'
 */
// export const bytesToUtf8: typeof bytesToUtf8_ = bytesToUtf8_;
// Is positive bigint
const isPosBig = (n) => typeof n === 'bigint' && _0n <= n;
function inRange(n, min, max) {
    return isPosBig(n) && isPosBig(min) && isPosBig(max) && min <= n && n < max;
}
/**
 * Asserts min <= n < max. NOTE: It's < max and not <= max.
 * @example
 * aInRange('x', x, 1n, 256n); // would assume x is in (1n..255n)
 */
function aInRange(title, n, min, max) {
    // Why min <= n < max and not a (min < n < max) OR b (min <= n <= max)?
    // consider P=256n, min=0n, max=P
    // - a for min=0 would require -1:          `inRange('x', x, -1n, P)`
    // - b would commonly require subtraction:  `inRange('x', x, 0n, P - 1n)`
    // - our way is the cleanest:               `inRange('x', x, 0n, P)
    if (!inRange(n, min, max))
        throw new Error('expected valid ' + title + ': ' + min + ' <= n < ' + max + ', got ' + n);
}
// Bit operations
/**
 * Calculates amount of bits in a bigint.
 * Same as `n.toString(2).length`
 * TODO: merge with nLength in modular
 */
function bitLen(n) {
    let len;
    for (len = 0; n > _0n; n >>= _1n, len += 1)
        ;
    return len;
}
/**
 * Gets single bit at position.
 * NOTE: first bit position is 0 (same as arrays)
 * Same as `!!+Array.from(n.toString(2)).reverse()[pos]`
 */
function bitGet(n, pos) {
    return (n >> BigInt(pos)) & _1n;
}
/**
 * Sets single bit at position.
 */
function bitSet(n, pos, value) {
    return n | ((value ? _1n : _0n) << BigInt(pos));
}
/**
 * Calculate mask for N bits. Not using ** operator with bigints because of old engines.
 * Same as BigInt(`0b${Array(i).fill('1').join('')}`)
 */
const bitMask = (n) => (_1n << BigInt(n)) - _1n;
/**
 * Minimal HMAC-DRBG from NIST 800-90 for RFC6979 sigs.
 * @returns function that will call DRBG until 2nd arg returns something meaningful
 * @example
 *   const drbg = createHmacDRBG<Key>(32, 32, hmac);
 *   drbg(seed, bytesToKey); // bytesToKey must return Key or undefined
 */
function createHmacDrbg(hashLen, qByteLen, hmacFn) {
    if (typeof hashLen !== 'number' || hashLen < 2)
        throw new Error('hashLen must be a number');
    if (typeof qByteLen !== 'number' || qByteLen < 2)
        throw new Error('qByteLen must be a number');
    if (typeof hmacFn !== 'function')
        throw new Error('hmacFn must be a function');
    // Step B, Step C: set hashLen to 8*ceil(hlen/8)
    const u8n = (len) => new Uint8Array(len); // creates Uint8Array
    const u8of = (byte) => Uint8Array.of(byte); // another shortcut
    let v = u8n(hashLen); // Minimal non-full-spec HMAC-DRBG from NIST 800-90 for RFC6979 sigs.
    let k = u8n(hashLen); // Steps B and C of RFC6979 3.2: set hashLen, in our case always same
    let i = 0; // Iterations counter, will throw when over 1000
    const reset = () => {
        v.fill(1);
        k.fill(0);
        i = 0;
    };
    const h = (...b) => hmacFn(k, v, ...b); // hmac(k)(v, ...values)
    const reseed = (seed = u8n(0)) => {
        // HMAC-DRBG reseed() function. Steps D-G
        k = h(u8of(0x00), seed); // k = hmac(k || v || 0x00 || seed)
        v = h(); // v = hmac(k || v)
        if (seed.length === 0)
            return;
        k = h(u8of(0x01), seed); // k = hmac(k || v || 0x01 || seed)
        v = h(); // v = hmac(k || v)
    };
    const gen = () => {
        // HMAC-DRBG generate() function
        if (i++ >= 1000)
            throw new Error('drbg: tried 1000 values');
        let len = 0;
        const out = [];
        while (len < qByteLen) {
            v = h();
            const sl = v.slice();
            out.push(sl);
            len += v.length;
        }
        return (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes)(...out);
    };
    const genUntil = (seed, pred) => {
        reset();
        reseed(seed); // Steps D-G
        let res = undefined; // Step H: grind until k is in [1..n-1]
        while (!(res = pred(gen())))
            reseed();
        reset();
        return res;
    };
    return genUntil;
}
// Validating curves and fields
const validatorFns = {
    bigint: (val) => typeof val === 'bigint',
    function: (val) => typeof val === 'function',
    boolean: (val) => typeof val === 'boolean',
    string: (val) => typeof val === 'string',
    stringOrUint8Array: (val) => typeof val === 'string' || (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_0__.isBytes)(val),
    isSafeInteger: (val) => Number.isSafeInteger(val),
    array: (val) => Array.isArray(val),
    field: (val, object) => object.Fp.isValid(val),
    hash: (val) => typeof val === 'function' && Number.isSafeInteger(val.outputLen),
};
// type Record<K extends string | number | symbol, T> = { [P in K]: T; }
function validateObject(object, validators, optValidators = {}) {
    const checkField = (fieldName, type, isOptional) => {
        const checkVal = validatorFns[type];
        if (typeof checkVal !== 'function')
            throw new Error('invalid validator function');
        const val = object[fieldName];
        if (isOptional && val === undefined)
            return;
        if (!checkVal(val, object)) {
            throw new Error('param ' + String(fieldName) + ' is invalid. Expected ' + type + ', got ' + val);
        }
    };
    for (const [fieldName, type] of Object.entries(validators))
        checkField(fieldName, type, false);
    for (const [fieldName, type] of Object.entries(optValidators))
        checkField(fieldName, type, true);
    return object;
}
// validate type tests
// const o: { a: number; b: number; c: number } = { a: 1, b: 5, c: 6 };
// const z0 = validateObject(o, { a: 'isSafeInteger' }, { c: 'bigint' }); // Ok!
// // Should fail type-check
// const z1 = validateObject(o, { a: 'tmp' }, { c: 'zz' });
// const z2 = validateObject(o, { a: 'isSafeInteger' }, { c: 'zz' });
// const z3 = validateObject(o, { test: 'boolean', z: 'bug' });
// const z4 = validateObject(o, { a: 'boolean', z: 'bug' });
function isHash(val) {
    return typeof val === 'function' && Number.isSafeInteger(val.outputLen);
}
function _validateObject(object, fields, optFields = {}) {
    if (!object || typeof object !== 'object')
        throw new Error('expected valid options object');
    function checkField(fieldName, expectedType, isOpt) {
        const val = object[fieldName];
        if (isOpt && val === undefined)
            return;
        const current = typeof val;
        if (current !== expectedType || val === null)
            throw new Error(`param "${fieldName}" is invalid: expected ${expectedType}, got ${current}`);
    }
    Object.entries(fields).forEach(([k, v]) => checkField(k, v, false));
    Object.entries(optFields).forEach(([k, v]) => checkField(k, v, true));
}
/**
 * throws not implemented error
 */
const notImplemented = () => {
    throw new Error('not implemented');
};
/**
 * Memoizes (caches) computation result.
 * Uses WeakMap: the value is going auto-cleaned by GC after last reference is removed.
 */
function memoized(fn) {
    const map = new WeakMap();
    return (arg, ...args) => {
        const val = map.get(arg);
        if (val !== undefined)
            return val;
        const computed = fn(arg, ...args);
        map.set(arg, computed);
        return computed;
    };
}
//# sourceMappingURL=utils.js.map

/***/ },

/***/ 13
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Chi: () => (/* binding */ Chi),
/* harmony export */   HashMD: () => (/* binding */ HashMD),
/* harmony export */   Maj: () => (/* binding */ Maj),
/* harmony export */   SHA224_IV: () => (/* binding */ SHA224_IV),
/* harmony export */   SHA256_IV: () => (/* binding */ SHA256_IV),
/* harmony export */   SHA384_IV: () => (/* binding */ SHA384_IV),
/* harmony export */   SHA512_IV: () => (/* binding */ SHA512_IV)
/* harmony export */ });
/* unused harmony export setBigUint64 */
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(976);
/**
 * Internal Merkle-Damgard hash utils.
 * @module
 */

/** Polyfill for Safari 14. https://caniuse.com/mdn-javascript_builtins_dataview_setbiguint64 */
function setBigUint64(view, byteOffset, value, isLE) {
    if (typeof view.setBigUint64 === 'function')
        return view.setBigUint64(byteOffset, value, isLE);
    const _32n = BigInt(32);
    const _u32_max = BigInt(0xffffffff);
    const wh = Number((value >> _32n) & _u32_max);
    const wl = Number(value & _u32_max);
    const h = isLE ? 4 : 0;
    const l = isLE ? 0 : 4;
    view.setUint32(byteOffset + h, wh, isLE);
    view.setUint32(byteOffset + l, wl, isLE);
}
/** Choice: a ? b : c */
function Chi(a, b, c) {
    return (a & b) ^ (~a & c);
}
/** Majority function, true if any two inputs is true. */
function Maj(a, b, c) {
    return (a & b) ^ (a & c) ^ (b & c);
}
/**
 * Merkle-Damgard hash construction base class.
 * Could be used to create MD5, RIPEMD, SHA1, SHA2.
 */
class HashMD extends _utils_js__WEBPACK_IMPORTED_MODULE_0__.Hash {
    constructor(blockLen, outputLen, padOffset, isLE) {
        super();
        this.finished = false;
        this.length = 0;
        this.pos = 0;
        this.destroyed = false;
        this.blockLen = blockLen;
        this.outputLen = outputLen;
        this.padOffset = padOffset;
        this.isLE = isLE;
        this.buffer = new Uint8Array(blockLen);
        this.view = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.createView)(this.buffer);
    }
    update(data) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        data = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(data);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(data);
        const { view, buffer, blockLen } = this;
        const len = data.length;
        for (let pos = 0; pos < len;) {
            const take = Math.min(blockLen - this.pos, len - pos);
            // Fast path: we have at least one block in input, cast it to view and process
            if (take === blockLen) {
                const dataView = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.createView)(data);
                for (; blockLen <= len - pos; pos += blockLen)
                    this.process(dataView, pos);
                continue;
            }
            buffer.set(data.subarray(pos, pos + take), this.pos);
            this.pos += take;
            pos += take;
            if (this.pos === blockLen) {
                this.process(view, 0);
                this.pos = 0;
            }
        }
        this.length += data.length;
        this.roundClean();
        return this;
    }
    digestInto(out) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aoutput)(out, this);
        this.finished = true;
        // Padding
        // We can avoid allocation of buffer for padding completely if it
        // was previously not allocated here. But it won't change performance.
        const { buffer, view, blockLen, isLE } = this;
        let { pos } = this;
        // append the bit '1' to the message
        buffer[pos++] = 0b10000000;
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(this.buffer.subarray(pos));
        // we have less than padOffset left in buffer, so we cannot put length in
        // current block, need process it and pad again
        if (this.padOffset > blockLen - pos) {
            this.process(view, 0);
            pos = 0;
        }
        // Pad until full block byte with zeros
        for (let i = pos; i < blockLen; i++)
            buffer[i] = 0;
        // Note: sha512 requires length to be 128bit integer, but length in JS will overflow before that
        // You need to write around 2 exabytes (u64_max / 8 / (1024**6)) for this to happen.
        // So we just write lowest 64 bits of that value.
        setBigUint64(view, blockLen - 8, BigInt(this.length * 8), isLE);
        this.process(view, 0);
        const oview = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.createView)(out);
        const len = this.outputLen;
        // NOTE: we do division by 4 later, which should be fused in single op with modulo by JIT
        if (len % 4)
            throw new Error('_sha2: outputLen should be aligned to 32bit');
        const outLen = len / 4;
        const state = this.get();
        if (outLen > state.length)
            throw new Error('_sha2: outputLen bigger than state');
        for (let i = 0; i < outLen; i++)
            oview.setUint32(4 * i, state[i], isLE);
    }
    digest() {
        const { buffer, outputLen } = this;
        this.digestInto(buffer);
        const res = buffer.slice(0, outputLen);
        this.destroy();
        return res;
    }
    _cloneInto(to) {
        to || (to = new this.constructor());
        to.set(...this.get());
        const { blockLen, buffer, length, finished, destroyed, pos } = this;
        to.destroyed = destroyed;
        to.finished = finished;
        to.length = length;
        to.pos = pos;
        if (length % blockLen)
            to.buffer.set(buffer);
        return to;
    }
    clone() {
        return this._cloneInto();
    }
}
/**
 * Initial SHA-2 state: fractional parts of square roots of first 16 primes 2..53.
 * Check out `test/misc/sha2-gen-iv.js` for recomputation guide.
 */
/** Initial SHA256 state. Bits 0..32 of frac part of sqrt of primes 2..19 */
const SHA256_IV = /* @__PURE__ */ Uint32Array.from([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
]);
/** Initial SHA224 state. Bits 32..64 of frac part of sqrt of primes 23..53 */
const SHA224_IV = /* @__PURE__ */ Uint32Array.from([
    0xc1059ed8, 0x367cd507, 0x3070dd17, 0xf70e5939, 0xffc00b31, 0x68581511, 0x64f98fa7, 0xbefa4fa4,
]);
/** Initial SHA384 state. Bits 0..64 of frac part of sqrt of primes 23..53 */
const SHA384_IV = /* @__PURE__ */ Uint32Array.from([
    0xcbbb9d5d, 0xc1059ed8, 0x629a292a, 0x367cd507, 0x9159015a, 0x3070dd17, 0x152fecd8, 0xf70e5939,
    0x67332667, 0xffc00b31, 0x8eb44a87, 0x68581511, 0xdb0c2e0d, 0x64f98fa7, 0x47b5481d, 0xbefa4fa4,
]);
/** Initial SHA512 state. Bits 0..64 of frac part of sqrt of primes 2..19 */
const SHA512_IV = /* @__PURE__ */ Uint32Array.from([
    0x6a09e667, 0xf3bcc908, 0xbb67ae85, 0x84caa73b, 0x3c6ef372, 0xfe94f82b, 0xa54ff53a, 0x5f1d36f1,
    0x510e527f, 0xade682d1, 0x9b05688c, 0x2b3e6c1f, 0x1f83d9ab, 0xfb41bd6b, 0x5be0cd19, 0x137e2179,
]);
//# sourceMappingURL=_md.js.map

/***/ },

/***/ 271
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   add: () => (/* binding */ add),
/* harmony export */   add3H: () => (/* binding */ add3H),
/* harmony export */   add3L: () => (/* binding */ add3L),
/* harmony export */   add4H: () => (/* binding */ add4H),
/* harmony export */   add4L: () => (/* binding */ add4L),
/* harmony export */   add5H: () => (/* binding */ add5H),
/* harmony export */   add5L: () => (/* binding */ add5L),
/* harmony export */   rotrBH: () => (/* binding */ rotrBH),
/* harmony export */   rotrBL: () => (/* binding */ rotrBL),
/* harmony export */   rotrSH: () => (/* binding */ rotrSH),
/* harmony export */   rotrSL: () => (/* binding */ rotrSL),
/* harmony export */   shrSH: () => (/* binding */ shrSH),
/* harmony export */   shrSL: () => (/* binding */ shrSL),
/* harmony export */   split: () => (/* binding */ split)
/* harmony export */ });
/* unused harmony exports fromBig, rotlBH, rotlBL, rotlSH, rotlSL, rotr32H, rotr32L, toBig */
/**
 * Internal helpers for u64. BigUint64Array is too slow as per 2025, so we implement it using Uint32Array.
 * @todo re-check https://issues.chromium.org/issues/42212588
 * @module
 */
const U32_MASK64 = /* @__PURE__ */ BigInt(2 ** 32 - 1);
const _32n = /* @__PURE__ */ BigInt(32);
function fromBig(n, le = false) {
    if (le)
        return { h: Number(n & U32_MASK64), l: Number((n >> _32n) & U32_MASK64) };
    return { h: Number((n >> _32n) & U32_MASK64) | 0, l: Number(n & U32_MASK64) | 0 };
}
function split(lst, le = false) {
    const len = lst.length;
    let Ah = new Uint32Array(len);
    let Al = new Uint32Array(len);
    for (let i = 0; i < len; i++) {
        const { h, l } = fromBig(lst[i], le);
        [Ah[i], Al[i]] = [h, l];
    }
    return [Ah, Al];
}
const toBig = (h, l) => (BigInt(h >>> 0) << _32n) | BigInt(l >>> 0);
// for Shift in [0, 32)
const shrSH = (h, _l, s) => h >>> s;
const shrSL = (h, l, s) => (h << (32 - s)) | (l >>> s);
// Right rotate for Shift in [1, 32)
const rotrSH = (h, l, s) => (h >>> s) | (l << (32 - s));
const rotrSL = (h, l, s) => (h << (32 - s)) | (l >>> s);
// Right rotate for Shift in (32, 64), NOTE: 32 is special case.
const rotrBH = (h, l, s) => (h << (64 - s)) | (l >>> (s - 32));
const rotrBL = (h, l, s) => (h >>> (s - 32)) | (l << (64 - s));
// Right rotate for shift===32 (just swaps l&h)
const rotr32H = (_h, l) => l;
const rotr32L = (h, _l) => h;
// Left rotate for Shift in [1, 32)
const rotlSH = (h, l, s) => (h << s) | (l >>> (32 - s));
const rotlSL = (h, l, s) => (l << s) | (h >>> (32 - s));
// Left rotate for Shift in (32, 64), NOTE: 32 is special case.
const rotlBH = (h, l, s) => (l << (s - 32)) | (h >>> (64 - s));
const rotlBL = (h, l, s) => (h << (s - 32)) | (l >>> (64 - s));
// JS uses 32-bit signed integers for bitwise operations which means we cannot
// simple take carry out of low bit sum by shift, we need to use division.
function add(Ah, Al, Bh, Bl) {
    const l = (Al >>> 0) + (Bl >>> 0);
    return { h: (Ah + Bh + ((l / 2 ** 32) | 0)) | 0, l: l | 0 };
}
// Addition with more than 2 elements
const add3L = (Al, Bl, Cl) => (Al >>> 0) + (Bl >>> 0) + (Cl >>> 0);
const add3H = (low, Ah, Bh, Ch) => (Ah + Bh + Ch + ((low / 2 ** 32) | 0)) | 0;
const add4L = (Al, Bl, Cl, Dl) => (Al >>> 0) + (Bl >>> 0) + (Cl >>> 0) + (Dl >>> 0);
const add4H = (low, Ah, Bh, Ch, Dh) => (Ah + Bh + Ch + Dh + ((low / 2 ** 32) | 0)) | 0;
const add5L = (Al, Bl, Cl, Dl, El) => (Al >>> 0) + (Bl >>> 0) + (Cl >>> 0) + (Dl >>> 0) + (El >>> 0);
const add5H = (low, Ah, Bh, Ch, Dh, Eh) => (Ah + Bh + Ch + Dh + Eh + ((low / 2 ** 32) | 0)) | 0;
// prettier-ignore

// prettier-ignore
const u64 = {
    fromBig, split, toBig,
    shrSH, shrSL,
    rotrSH, rotrSL, rotrBH, rotrBL,
    rotr32H, rotr32L,
    rotlSH, rotlSL, rotlBH, rotlBL,
    add, add3L, add3H, add4L, add4H, add5H, add5L,
};
/* unused harmony default export */ var __WEBPACK_DEFAULT_EXPORT__ = ((/* unused pure expression or super */ null && (u64)));
//# sourceMappingURL=_u64.js.map

/***/ },

/***/ 160
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   crypto: () => (/* binding */ crypto)
/* harmony export */ });
const crypto = typeof globalThis === 'object' && 'crypto' in globalThis ? globalThis.crypto : undefined;
//# sourceMappingURL=crypto.js.map

/***/ },

/***/ 962
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   expand: () => (/* binding */ expand),
/* harmony export */   extract: () => (/* binding */ extract)
/* harmony export */ });
/* unused harmony export hkdf */
/* harmony import */ var _hmac_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(454);
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(976);
/**
 * HKDF (RFC 5869): extract + expand in one step.
 * See https://soatok.blog/2021/11/17/understanding-hkdf/.
 * @module
 */


/**
 * HKDF-extract from spec. Less important part. `HKDF-Extract(IKM, salt) -> PRK`
 * Arguments position differs from spec (IKM is first one, since it is not optional)
 * @param hash - hash function that would be used (e.g. sha256)
 * @param ikm - input keying material, the initial key
 * @param salt - optional salt value (a non-secret random value)
 */
function extract(hash, ikm, salt) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.ahash)(hash);
    // NOTE: some libraries treat zero-length array as 'not provided';
    // we don't, since we have undefined as 'not provided'
    // https://github.com/RustCrypto/KDFs/issues/15
    if (salt === undefined)
        salt = new Uint8Array(hash.outputLen);
    return (0,_hmac_js__WEBPACK_IMPORTED_MODULE_0__.hmac)(hash, (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.toBytes)(salt), (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.toBytes)(ikm));
}
const HKDF_COUNTER = /* @__PURE__ */ Uint8Array.from([0]);
const EMPTY_BUFFER = /* @__PURE__ */ Uint8Array.of();
/**
 * HKDF-expand from the spec. The most important part. `HKDF-Expand(PRK, info, L) -> OKM`
 * @param hash - hash function that would be used (e.g. sha256)
 * @param prk - a pseudorandom key of at least HashLen octets (usually, the output from the extract step)
 * @param info - optional context and application specific information (can be a zero-length string)
 * @param length - length of output keying material in bytes
 */
function expand(hash, prk, info, length = 32) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.ahash)(hash);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.anumber)(length);
    const olen = hash.outputLen;
    if (length > 255 * olen)
        throw new Error('Length should be <= 255*HashLen');
    const blocks = Math.ceil(length / olen);
    if (info === undefined)
        info = EMPTY_BUFFER;
    // first L(ength) octets of T
    const okm = new Uint8Array(blocks * olen);
    // Re-use HMAC instance between blocks
    const HMAC = _hmac_js__WEBPACK_IMPORTED_MODULE_0__.hmac.create(hash, prk);
    const HMACTmp = HMAC._cloneInto();
    const T = new Uint8Array(HMAC.outputLen);
    for (let counter = 0; counter < blocks; counter++) {
        HKDF_COUNTER[0] = counter + 1;
        // T(0) = empty string (zero length)
        // T(N) = HMAC-Hash(PRK, T(N-1) | info | N)
        HMACTmp.update(counter === 0 ? EMPTY_BUFFER : T)
            .update(info)
            .update(HKDF_COUNTER)
            .digestInto(T);
        okm.set(T, olen * counter);
        HMAC._cloneInto(HMACTmp);
    }
    HMAC.destroy();
    HMACTmp.destroy();
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(T, HKDF_COUNTER);
    return okm.slice(0, length);
}
/**
 * HKDF (RFC 5869): derive keys from an initial input.
 * Combines hkdf_extract + hkdf_expand in one step
 * @param hash - hash function that would be used (e.g. sha256)
 * @param ikm - input keying material, the initial key
 * @param salt - optional salt value (a non-secret random value)
 * @param info - optional context and application specific information (can be a zero-length string)
 * @param length - length of output keying material in bytes
 * @example
 * import { hkdf } from '@noble/hashes/hkdf';
 * import { sha256 } from '@noble/hashes/sha2';
 * import { randomBytes } from '@noble/hashes/utils';
 * const inputKey = randomBytes(32);
 * const salt = randomBytes(32);
 * const info = 'application-key';
 * const hk1 = hkdf(sha256, inputKey, salt, info, 32);
 */
const hkdf = (hash, ikm, salt, info, length) => expand(hash, extract(hash, ikm, salt), info, length);
//# sourceMappingURL=hkdf.js.map

/***/ },

/***/ 454
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   hmac: () => (/* binding */ hmac)
/* harmony export */ });
/* unused harmony export HMAC */
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(976);
/**
 * HMAC: RFC2104 message authentication code.
 * @module
 */

class HMAC extends _utils_js__WEBPACK_IMPORTED_MODULE_0__.Hash {
    constructor(hash, _key) {
        super();
        this.finished = false;
        this.destroyed = false;
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ahash)(hash);
        const key = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(_key);
        this.iHash = hash.create();
        if (typeof this.iHash.update !== 'function')
            throw new Error('Expected instance of class which extends utils.Hash');
        this.blockLen = this.iHash.blockLen;
        this.outputLen = this.iHash.outputLen;
        const blockLen = this.blockLen;
        const pad = new Uint8Array(blockLen);
        // blockLen can be bigger than outputLen
        pad.set(key.length > blockLen ? hash.create().update(key).digest() : key);
        for (let i = 0; i < pad.length; i++)
            pad[i] ^= 0x36;
        this.iHash.update(pad);
        // By doing update (processing of first block) of outer hash here we can re-use it between multiple calls via clone
        this.oHash = hash.create();
        // Undo internal XOR && apply outer XOR
        for (let i = 0; i < pad.length; i++)
            pad[i] ^= 0x36 ^ 0x5c;
        this.oHash.update(pad);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(pad);
    }
    update(buf) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        this.iHash.update(buf);
        return this;
    }
    digestInto(out) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(out, this.outputLen);
        this.finished = true;
        this.iHash.digestInto(out);
        this.oHash.update(out);
        this.oHash.digestInto(out);
        this.destroy();
    }
    digest() {
        const out = new Uint8Array(this.oHash.outputLen);
        this.digestInto(out);
        return out;
    }
    _cloneInto(to) {
        // Create new instance without calling constructor since key already in state and we don't know it.
        to || (to = Object.create(Object.getPrototypeOf(this), {}));
        const { oHash, iHash, finished, destroyed, blockLen, outputLen } = this;
        to = to;
        to.finished = finished;
        to.destroyed = destroyed;
        to.blockLen = blockLen;
        to.outputLen = outputLen;
        to.oHash = oHash._cloneInto(to.oHash);
        to.iHash = iHash._cloneInto(to.iHash);
        return to;
    }
    clone() {
        return this._cloneInto();
    }
    destroy() {
        this.destroyed = true;
        this.oHash.destroy();
        this.iHash.destroy();
    }
}
/**
 * HMAC: RFC2104 message authentication code.
 * @param hash - function that would be used e.g. sha256
 * @param key - message key
 * @param message - message data
 * @example
 * import { hmac } from '@noble/hashes/hmac';
 * import { sha256 } from '@noble/hashes/sha2';
 * const mac1 = hmac(sha256, 'key', 'message');
 */
const hmac = (hash, key, message) => new HMAC(hash, key).update(message).digest();
hmac.create = (hash, key) => new HMAC(hash, key);
//# sourceMappingURL=hmac.js.map

/***/ },

/***/ 200
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   pbkdf2: () => (/* binding */ pbkdf2)
/* harmony export */ });
/* unused harmony export pbkdf2Async */
/* unused harmony import specifier */ var createView;
/* unused harmony import specifier */ var asyncLoop;
/* harmony import */ var _hmac_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(454);
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(976);
/**
 * PBKDF (RFC 2898). Can be used to create a key from password and salt.
 * @module
 */

// prettier-ignore

// Common prologue and epilogue for sync/async functions
function pbkdf2Init(hash, _password, _salt, _opts) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.ahash)(hash);
    const opts = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.checkOpts)({ dkLen: 32, asyncTick: 10 }, _opts);
    const { c, dkLen, asyncTick } = opts;
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.anumber)(c);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.anumber)(dkLen);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.anumber)(asyncTick);
    if (c < 1)
        throw new Error('iterations (c) should be >= 1');
    const password = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.kdfInputToBytes)(_password);
    const salt = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.kdfInputToBytes)(_salt);
    // DK = PBKDF2(PRF, Password, Salt, c, dkLen);
    const DK = new Uint8Array(dkLen);
    // U1 = PRF(Password, Salt + INT_32_BE(i))
    const PRF = _hmac_js__WEBPACK_IMPORTED_MODULE_0__.hmac.create(hash, password);
    const PRFSalt = PRF._cloneInto().update(salt);
    return { c, dkLen, asyncTick, DK, PRF, PRFSalt };
}
function pbkdf2Output(PRF, PRFSalt, DK, prfW, u) {
    PRF.destroy();
    PRFSalt.destroy();
    if (prfW)
        prfW.destroy();
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(u);
    return DK;
}
/**
 * PBKDF2-HMAC: RFC 2898 key derivation function
 * @param hash - hash function that would be used e.g. sha256
 * @param password - password from which a derived key is generated
 * @param salt - cryptographic salt
 * @param opts - {c, dkLen} where c is work factor and dkLen is output message size
 * @example
 * const key = pbkdf2(sha256, 'password', 'salt', { dkLen: 32, c: Math.pow(2, 18) });
 */
function pbkdf2(hash, password, salt, opts) {
    const { c, dkLen, DK, PRF, PRFSalt } = pbkdf2Init(hash, password, salt, opts);
    let prfW; // Working copy
    const arr = new Uint8Array(4);
    const view = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createView)(arr);
    const u = new Uint8Array(PRF.outputLen);
    // DK = T1 + T2 + ⋯ + Tdklen/hlen
    for (let ti = 1, pos = 0; pos < dkLen; ti++, pos += PRF.outputLen) {
        // Ti = F(Password, Salt, c, i)
        const Ti = DK.subarray(pos, pos + PRF.outputLen);
        view.setInt32(0, ti, false);
        // F(Password, Salt, c, i) = U1 ^ U2 ^ ⋯ ^ Uc
        // U1 = PRF(Password, Salt + INT_32_BE(i))
        (prfW = PRFSalt._cloneInto(prfW)).update(arr).digestInto(u);
        Ti.set(u.subarray(0, Ti.length));
        for (let ui = 1; ui < c; ui++) {
            // Uc = PRF(Password, Uc−1)
            PRF._cloneInto(prfW).update(u).digestInto(u);
            for (let i = 0; i < Ti.length; i++)
                Ti[i] ^= u[i];
        }
    }
    return pbkdf2Output(PRF, PRFSalt, DK, prfW, u);
}
/**
 * PBKDF2-HMAC: RFC 2898 key derivation function. Async version.
 * @example
 * await pbkdf2Async(sha256, 'password', 'salt', { dkLen: 32, c: 500_000 });
 */
async function pbkdf2Async(hash, password, salt, opts) {
    const { c, dkLen, asyncTick, DK, PRF, PRFSalt } = pbkdf2Init(hash, password, salt, opts);
    let prfW; // Working copy
    const arr = new Uint8Array(4);
    const view = createView(arr);
    const u = new Uint8Array(PRF.outputLen);
    // DK = T1 + T2 + ⋯ + Tdklen/hlen
    for (let ti = 1, pos = 0; pos < dkLen; ti++, pos += PRF.outputLen) {
        // Ti = F(Password, Salt, c, i)
        const Ti = DK.subarray(pos, pos + PRF.outputLen);
        view.setInt32(0, ti, false);
        // F(Password, Salt, c, i) = U1 ^ U2 ^ ⋯ ^ Uc
        // U1 = PRF(Password, Salt + INT_32_BE(i))
        (prfW = PRFSalt._cloneInto(prfW)).update(arr).digestInto(u);
        Ti.set(u.subarray(0, Ti.length));
        await asyncLoop(c - 1, asyncTick, () => {
            // Uc = PRF(Password, Uc−1)
            PRF._cloneInto(prfW).update(u).digestInto(u);
            for (let i = 0; i < Ti.length; i++)
                Ti[i] ^= u[i];
        });
    }
    return pbkdf2Output(PRF, PRFSalt, DK, prfW, u);
}
//# sourceMappingURL=pbkdf2.js.map

/***/ },

/***/ 430
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   scryptAsync: () => (/* binding */ scryptAsync)
/* harmony export */ });
/* unused harmony export scrypt */
/* unused harmony import specifier */ var swap32IfBE;
/* harmony import */ var _pbkdf2_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(200);
/* harmony import */ var _sha2_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(429);
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(976);
/**
 * RFC 7914 Scrypt KDF. Can be used to create a key from password and salt.
 * @module
 */


// prettier-ignore

// The main Scrypt loop: uses Salsa extensively.
// Six versions of the function were tried, this is the fastest one.
// prettier-ignore
function XorAndSalsa(prev, pi, input, ii, out, oi) {
    // Based on https://cr.yp.to/salsa20.html
    // Xor blocks
    let y00 = prev[pi++] ^ input[ii++], y01 = prev[pi++] ^ input[ii++];
    let y02 = prev[pi++] ^ input[ii++], y03 = prev[pi++] ^ input[ii++];
    let y04 = prev[pi++] ^ input[ii++], y05 = prev[pi++] ^ input[ii++];
    let y06 = prev[pi++] ^ input[ii++], y07 = prev[pi++] ^ input[ii++];
    let y08 = prev[pi++] ^ input[ii++], y09 = prev[pi++] ^ input[ii++];
    let y10 = prev[pi++] ^ input[ii++], y11 = prev[pi++] ^ input[ii++];
    let y12 = prev[pi++] ^ input[ii++], y13 = prev[pi++] ^ input[ii++];
    let y14 = prev[pi++] ^ input[ii++], y15 = prev[pi++] ^ input[ii++];
    // Save state to temporary variables (salsa)
    let x00 = y00, x01 = y01, x02 = y02, x03 = y03, x04 = y04, x05 = y05, x06 = y06, x07 = y07, x08 = y08, x09 = y09, x10 = y10, x11 = y11, x12 = y12, x13 = y13, x14 = y14, x15 = y15;
    // Main loop (salsa)
    for (let i = 0; i < 8; i += 2) {
        x04 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x00 + x12 | 0, 7);
        x08 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x04 + x00 | 0, 9);
        x12 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x08 + x04 | 0, 13);
        x00 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x12 + x08 | 0, 18);
        x09 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x05 + x01 | 0, 7);
        x13 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x09 + x05 | 0, 9);
        x01 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x13 + x09 | 0, 13);
        x05 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x01 + x13 | 0, 18);
        x14 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x10 + x06 | 0, 7);
        x02 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x14 + x10 | 0, 9);
        x06 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x02 + x14 | 0, 13);
        x10 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x06 + x02 | 0, 18);
        x03 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x15 + x11 | 0, 7);
        x07 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x03 + x15 | 0, 9);
        x11 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x07 + x03 | 0, 13);
        x15 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x11 + x07 | 0, 18);
        x01 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x00 + x03 | 0, 7);
        x02 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x01 + x00 | 0, 9);
        x03 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x02 + x01 | 0, 13);
        x00 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x03 + x02 | 0, 18);
        x06 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x05 + x04 | 0, 7);
        x07 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x06 + x05 | 0, 9);
        x04 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x07 + x06 | 0, 13);
        x05 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x04 + x07 | 0, 18);
        x11 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x10 + x09 | 0, 7);
        x08 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x11 + x10 | 0, 9);
        x09 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x08 + x11 | 0, 13);
        x10 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x09 + x08 | 0, 18);
        x12 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x15 + x14 | 0, 7);
        x13 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x12 + x15 | 0, 9);
        x14 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x13 + x12 | 0, 13);
        x15 ^= (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotl)(x14 + x13 | 0, 18);
    }
    // Write output (salsa)
    out[oi++] = (y00 + x00) | 0;
    out[oi++] = (y01 + x01) | 0;
    out[oi++] = (y02 + x02) | 0;
    out[oi++] = (y03 + x03) | 0;
    out[oi++] = (y04 + x04) | 0;
    out[oi++] = (y05 + x05) | 0;
    out[oi++] = (y06 + x06) | 0;
    out[oi++] = (y07 + x07) | 0;
    out[oi++] = (y08 + x08) | 0;
    out[oi++] = (y09 + x09) | 0;
    out[oi++] = (y10 + x10) | 0;
    out[oi++] = (y11 + x11) | 0;
    out[oi++] = (y12 + x12) | 0;
    out[oi++] = (y13 + x13) | 0;
    out[oi++] = (y14 + x14) | 0;
    out[oi++] = (y15 + x15) | 0;
}
function BlockMix(input, ii, out, oi, r) {
    // The block B is r 128-byte chunks (which is equivalent of 2r 64-byte chunks)
    let head = oi + 0;
    let tail = oi + 16 * r;
    for (let i = 0; i < 16; i++)
        out[tail + i] = input[ii + (2 * r - 1) * 16 + i]; // X ← B[2r−1]
    for (let i = 0; i < r; i++, head += 16, ii += 16) {
        // We write odd & even Yi at same time. Even: 0bXXXXX0 Odd:  0bXXXXX1
        XorAndSalsa(out, tail, input, ii, out, head); // head[i] = Salsa(blockIn[2*i] ^ tail[i-1])
        if (i > 0)
            tail += 16; // First iteration overwrites tmp value in tail
        XorAndSalsa(out, head, input, (ii += 16), out, tail); // tail[i] = Salsa(blockIn[2*i+1] ^ head[i])
    }
}
// Common prologue and epilogue for sync/async functions
function scryptInit(password, salt, _opts) {
    // Maxmem - 1GB+1KB by default
    const opts = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.checkOpts)({
        dkLen: 32,
        asyncTick: 10,
        maxmem: 1024 ** 3 + 1024,
    }, _opts);
    const { N, r, p, dkLen, asyncTick, maxmem, onProgress } = opts;
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.anumber)(N);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.anumber)(r);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.anumber)(p);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.anumber)(dkLen);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.anumber)(asyncTick);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.anumber)(maxmem);
    if (onProgress !== undefined && typeof onProgress !== 'function')
        throw new Error('progressCb should be function');
    const blockSize = 128 * r;
    const blockSize32 = blockSize / 4;
    // Max N is 2^32 (Integrify is 32-bit). Real limit is 2^22: JS engines Uint8Array limit is 4GB in 2024.
    // Spec check `N >= 2^(blockSize / 8)` is not done for compat with popular libs,
    // which used incorrect r: 1, p: 8. Also, the check seems to be a spec error:
    // https://www.rfc-editor.org/errata_search.php?rfc=7914
    const pow32 = Math.pow(2, 32);
    if (N <= 1 || (N & (N - 1)) !== 0 || N > pow32) {
        throw new Error('Scrypt: N must be larger than 1, a power of 2, and less than 2^32');
    }
    if (p < 0 || p > ((pow32 - 1) * 32) / blockSize) {
        throw new Error('Scrypt: p must be a positive integer less than or equal to ((2^32 - 1) * 32) / (128 * r)');
    }
    if (dkLen < 0 || dkLen > (pow32 - 1) * 32) {
        throw new Error('Scrypt: dkLen should be positive integer less than or equal to (2^32 - 1) * 32');
    }
    const memUsed = blockSize * (N + p);
    if (memUsed > maxmem) {
        throw new Error('Scrypt: memused is bigger than maxMem. Expected 128 * r * (N + p) > maxmem of ' + maxmem);
    }
    // [B0...Bp−1] ← PBKDF2HMAC-SHA256(Passphrase, Salt, 1, blockSize*ParallelizationFactor)
    // Since it has only one iteration there is no reason to use async variant
    const B = (0,_pbkdf2_js__WEBPACK_IMPORTED_MODULE_0__.pbkdf2)(_sha2_js__WEBPACK_IMPORTED_MODULE_1__.sha256, password, salt, { c: 1, dkLen: blockSize * p });
    const B32 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.u32)(B);
    // Re-used between parallel iterations. Array(iterations) of B
    const V = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.u32)(new Uint8Array(blockSize * N));
    const tmp = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.u32)(new Uint8Array(blockSize));
    let blockMixCb = () => { };
    if (onProgress) {
        const totalBlockMix = 2 * N * p;
        // Invoke callback if progress changes from 10.01 to 10.02
        // Allows to draw smooth progress bar on up to 8K screen
        const callbackPer = Math.max(Math.floor(totalBlockMix / 10000), 1);
        let blockMixCnt = 0;
        blockMixCb = () => {
            blockMixCnt++;
            if (onProgress && (!(blockMixCnt % callbackPer) || blockMixCnt === totalBlockMix))
                onProgress(blockMixCnt / totalBlockMix);
        };
    }
    return { N, r, p, dkLen, blockSize32, V, B32, B, tmp, blockMixCb, asyncTick };
}
function scryptOutput(password, dkLen, B, V, tmp) {
    const res = (0,_pbkdf2_js__WEBPACK_IMPORTED_MODULE_0__.pbkdf2)(_sha2_js__WEBPACK_IMPORTED_MODULE_1__.sha256, password, B, { c: 1, dkLen });
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.clean)(B, V, tmp);
    return res;
}
/**
 * Scrypt KDF from RFC 7914.
 * @param password - pass
 * @param salt - salt
 * @param opts - parameters
 * - `N` is cpu/mem work factor (power of 2 e.g. 2**18)
 * - `r` is block size (8 is common), fine-tunes sequential memory read size and performance
 * - `p` is parallelization factor (1 is common)
 * - `dkLen` is output key length in bytes e.g. 32.
 * - `asyncTick` - (default: 10) max time in ms for which async function can block execution
 * - `maxmem` - (default: `1024 ** 3 + 1024` aka 1GB+1KB). A limit that the app could use for scrypt
 * - `onProgress` - callback function that would be executed for progress report
 * @returns Derived key
 * @example
 * scrypt('password', 'salt', { N: 2**18, r: 8, p: 1, dkLen: 32 });
 */
function scrypt(password, salt, opts) {
    const { N, r, p, dkLen, blockSize32, V, B32, B, tmp, blockMixCb } = scryptInit(password, salt, opts);
    swap32IfBE(B32);
    for (let pi = 0; pi < p; pi++) {
        const Pi = blockSize32 * pi;
        for (let i = 0; i < blockSize32; i++)
            V[i] = B32[Pi + i]; // V[0] = B[i]
        for (let i = 0, pos = 0; i < N - 1; i++) {
            BlockMix(V, pos, V, (pos += blockSize32), r); // V[i] = BlockMix(V[i-1]);
            blockMixCb();
        }
        BlockMix(V, (N - 1) * blockSize32, B32, Pi, r); // Process last element
        blockMixCb();
        for (let i = 0; i < N; i++) {
            // First u32 of the last 64-byte block (u32 is LE)
            const j = B32[Pi + blockSize32 - 16] % N; // j = Integrify(X) % iterations
            for (let k = 0; k < blockSize32; k++)
                tmp[k] = B32[Pi + k] ^ V[j * blockSize32 + k]; // tmp = B ^ V[j]
            BlockMix(tmp, 0, B32, Pi, r); // B = BlockMix(B ^ V[j])
            blockMixCb();
        }
    }
    swap32IfBE(B32);
    return scryptOutput(password, dkLen, B, V, tmp);
}
/**
 * Scrypt KDF from RFC 7914. Async version.
 * @example
 * await scryptAsync('password', 'salt', { N: 2**18, r: 8, p: 1, dkLen: 32 });
 */
async function scryptAsync(password, salt, opts) {
    const { N, r, p, dkLen, blockSize32, V, B32, B, tmp, blockMixCb, asyncTick } = scryptInit(password, salt, opts);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.swap32IfBE)(B32);
    for (let pi = 0; pi < p; pi++) {
        const Pi = blockSize32 * pi;
        for (let i = 0; i < blockSize32; i++)
            V[i] = B32[Pi + i]; // V[0] = B[i]
        let pos = 0;
        await (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.asyncLoop)(N - 1, asyncTick, () => {
            BlockMix(V, pos, V, (pos += blockSize32), r); // V[i] = BlockMix(V[i-1]);
            blockMixCb();
        });
        BlockMix(V, (N - 1) * blockSize32, B32, Pi, r); // Process last element
        blockMixCb();
        await (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.asyncLoop)(N, asyncTick, () => {
            // First u32 of the last 64-byte block (u32 is LE)
            const j = B32[Pi + blockSize32 - 16] % N; // j = Integrify(X) % iterations
            for (let k = 0; k < blockSize32; k++)
                tmp[k] = B32[Pi + k] ^ V[j * blockSize32 + k]; // tmp = B ^ V[j]
            BlockMix(tmp, 0, B32, Pi, r); // B = BlockMix(B ^ V[j])
            blockMixCb();
        });
    }
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.swap32IfBE)(B32);
    return scryptOutput(password, dkLen, B, V, tmp);
}
//# sourceMappingURL=scrypt.js.map

/***/ },

/***/ 429
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   sha256: () => (/* binding */ sha256)
/* harmony export */ });
/* unused harmony exports SHA256, SHA224, SHA512, SHA384, SHA512_224, SHA512_256, sha224, sha512, sha384, sha512_256, sha512_224 */
/* unused harmony import specifier */ var createHasher;
/* harmony import */ var _md_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(13);
/* harmony import */ var _u64_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(271);
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(976);
/**
 * SHA2 hash function. A.k.a. sha256, sha384, sha512, sha512_224, sha512_256.
 * SHA256 is the fastest hash implementable in JS, even faster than Blake3.
 * Check out [RFC 4634](https://datatracker.ietf.org/doc/html/rfc4634) and
 * [FIPS 180-4](https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf).
 * @module
 */



/**
 * Round constants:
 * First 32 bits of fractional parts of the cube roots of the first 64 primes 2..311)
 */
// prettier-ignore
const SHA256_K = /* @__PURE__ */ Uint32Array.from([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
]);
/** Reusable temporary buffer. "W" comes straight from spec. */
const SHA256_W = /* @__PURE__ */ new Uint32Array(64);
class SHA256 extends _md_js__WEBPACK_IMPORTED_MODULE_0__.HashMD {
    constructor(outputLen = 32) {
        super(64, outputLen, 8, false);
        // We cannot use array here since array allows indexing by variable
        // which means optimizer/compiler cannot use registers.
        this.A = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[0] | 0;
        this.B = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[1] | 0;
        this.C = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[2] | 0;
        this.D = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[3] | 0;
        this.E = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[4] | 0;
        this.F = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[5] | 0;
        this.G = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[6] | 0;
        this.H = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[7] | 0;
    }
    get() {
        const { A, B, C, D, E, F, G, H } = this;
        return [A, B, C, D, E, F, G, H];
    }
    // prettier-ignore
    set(A, B, C, D, E, F, G, H) {
        this.A = A | 0;
        this.B = B | 0;
        this.C = C | 0;
        this.D = D | 0;
        this.E = E | 0;
        this.F = F | 0;
        this.G = G | 0;
        this.H = H | 0;
    }
    process(view, offset) {
        // Extend the first 16 words into the remaining 48 words w[16..63] of the message schedule array
        for (let i = 0; i < 16; i++, offset += 4)
            SHA256_W[i] = view.getUint32(offset, false);
        for (let i = 16; i < 64; i++) {
            const W15 = SHA256_W[i - 15];
            const W2 = SHA256_W[i - 2];
            const s0 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(W15, 7) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(W15, 18) ^ (W15 >>> 3);
            const s1 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(W2, 17) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(W2, 19) ^ (W2 >>> 10);
            SHA256_W[i] = (s1 + SHA256_W[i - 7] + s0 + SHA256_W[i - 16]) | 0;
        }
        // Compression function main loop, 64 rounds
        let { A, B, C, D, E, F, G, H } = this;
        for (let i = 0; i < 64; i++) {
            const sigma1 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(E, 6) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(E, 11) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(E, 25);
            const T1 = (H + sigma1 + (0,_md_js__WEBPACK_IMPORTED_MODULE_0__.Chi)(E, F, G) + SHA256_K[i] + SHA256_W[i]) | 0;
            const sigma0 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(A, 2) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(A, 13) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.rotr)(A, 22);
            const T2 = (sigma0 + (0,_md_js__WEBPACK_IMPORTED_MODULE_0__.Maj)(A, B, C)) | 0;
            H = G;
            G = F;
            F = E;
            E = (D + T1) | 0;
            D = C;
            C = B;
            B = A;
            A = (T1 + T2) | 0;
        }
        // Add the compressed chunk to the current hash value
        A = (A + this.A) | 0;
        B = (B + this.B) | 0;
        C = (C + this.C) | 0;
        D = (D + this.D) | 0;
        E = (E + this.E) | 0;
        F = (F + this.F) | 0;
        G = (G + this.G) | 0;
        H = (H + this.H) | 0;
        this.set(A, B, C, D, E, F, G, H);
    }
    roundClean() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.clean)(SHA256_W);
    }
    destroy() {
        this.set(0, 0, 0, 0, 0, 0, 0, 0);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.clean)(this.buffer);
    }
}
class SHA224 extends SHA256 {
    constructor() {
        super(28);
        this.A = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[0] | 0;
        this.B = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[1] | 0;
        this.C = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[2] | 0;
        this.D = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[3] | 0;
        this.E = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[4] | 0;
        this.F = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[5] | 0;
        this.G = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[6] | 0;
        this.H = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[7] | 0;
    }
}
// SHA2-512 is slower than sha256 in js because u64 operations are slow.
// Round contants
// First 32 bits of the fractional parts of the cube roots of the first 80 primes 2..409
// prettier-ignore
const K512 = /* @__PURE__ */ (() => _u64_js__WEBPACK_IMPORTED_MODULE_1__.split([
    '0x428a2f98d728ae22', '0x7137449123ef65cd', '0xb5c0fbcfec4d3b2f', '0xe9b5dba58189dbbc',
    '0x3956c25bf348b538', '0x59f111f1b605d019', '0x923f82a4af194f9b', '0xab1c5ed5da6d8118',
    '0xd807aa98a3030242', '0x12835b0145706fbe', '0x243185be4ee4b28c', '0x550c7dc3d5ffb4e2',
    '0x72be5d74f27b896f', '0x80deb1fe3b1696b1', '0x9bdc06a725c71235', '0xc19bf174cf692694',
    '0xe49b69c19ef14ad2', '0xefbe4786384f25e3', '0x0fc19dc68b8cd5b5', '0x240ca1cc77ac9c65',
    '0x2de92c6f592b0275', '0x4a7484aa6ea6e483', '0x5cb0a9dcbd41fbd4', '0x76f988da831153b5',
    '0x983e5152ee66dfab', '0xa831c66d2db43210', '0xb00327c898fb213f', '0xbf597fc7beef0ee4',
    '0xc6e00bf33da88fc2', '0xd5a79147930aa725', '0x06ca6351e003826f', '0x142929670a0e6e70',
    '0x27b70a8546d22ffc', '0x2e1b21385c26c926', '0x4d2c6dfc5ac42aed', '0x53380d139d95b3df',
    '0x650a73548baf63de', '0x766a0abb3c77b2a8', '0x81c2c92e47edaee6', '0x92722c851482353b',
    '0xa2bfe8a14cf10364', '0xa81a664bbc423001', '0xc24b8b70d0f89791', '0xc76c51a30654be30',
    '0xd192e819d6ef5218', '0xd69906245565a910', '0xf40e35855771202a', '0x106aa07032bbd1b8',
    '0x19a4c116b8d2d0c8', '0x1e376c085141ab53', '0x2748774cdf8eeb99', '0x34b0bcb5e19b48a8',
    '0x391c0cb3c5c95a63', '0x4ed8aa4ae3418acb', '0x5b9cca4f7763e373', '0x682e6ff3d6b2b8a3',
    '0x748f82ee5defb2fc', '0x78a5636f43172f60', '0x84c87814a1f0ab72', '0x8cc702081a6439ec',
    '0x90befffa23631e28', '0xa4506cebde82bde9', '0xbef9a3f7b2c67915', '0xc67178f2e372532b',
    '0xca273eceea26619c', '0xd186b8c721c0c207', '0xeada7dd6cde0eb1e', '0xf57d4f7fee6ed178',
    '0x06f067aa72176fba', '0x0a637dc5a2c898a6', '0x113f9804bef90dae', '0x1b710b35131c471b',
    '0x28db77f523047d84', '0x32caab7b40c72493', '0x3c9ebe0a15c9bebc', '0x431d67c49c100d4c',
    '0x4cc5d4becb3e42b6', '0x597f299cfc657e2a', '0x5fcb6fab3ad6faec', '0x6c44198c4a475817'
].map(n => BigInt(n))))();
const SHA512_Kh = /* @__PURE__ */ (() => K512[0])();
const SHA512_Kl = /* @__PURE__ */ (() => K512[1])();
// Reusable temporary buffers
const SHA512_W_H = /* @__PURE__ */ new Uint32Array(80);
const SHA512_W_L = /* @__PURE__ */ new Uint32Array(80);
class SHA512 extends _md_js__WEBPACK_IMPORTED_MODULE_0__.HashMD {
    constructor(outputLen = 64) {
        super(128, outputLen, 16, false);
        // We cannot use array here since array allows indexing by variable
        // which means optimizer/compiler cannot use registers.
        // h -- high 32 bits, l -- low 32 bits
        this.Ah = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[0] | 0;
        this.Al = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[1] | 0;
        this.Bh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[2] | 0;
        this.Bl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[3] | 0;
        this.Ch = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[4] | 0;
        this.Cl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[5] | 0;
        this.Dh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[6] | 0;
        this.Dl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[7] | 0;
        this.Eh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[8] | 0;
        this.El = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[9] | 0;
        this.Fh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[10] | 0;
        this.Fl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[11] | 0;
        this.Gh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[12] | 0;
        this.Gl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[13] | 0;
        this.Hh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[14] | 0;
        this.Hl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[15] | 0;
    }
    // prettier-ignore
    get() {
        const { Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl } = this;
        return [Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl];
    }
    // prettier-ignore
    set(Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl) {
        this.Ah = Ah | 0;
        this.Al = Al | 0;
        this.Bh = Bh | 0;
        this.Bl = Bl | 0;
        this.Ch = Ch | 0;
        this.Cl = Cl | 0;
        this.Dh = Dh | 0;
        this.Dl = Dl | 0;
        this.Eh = Eh | 0;
        this.El = El | 0;
        this.Fh = Fh | 0;
        this.Fl = Fl | 0;
        this.Gh = Gh | 0;
        this.Gl = Gl | 0;
        this.Hh = Hh | 0;
        this.Hl = Hl | 0;
    }
    process(view, offset) {
        // Extend the first 16 words into the remaining 64 words w[16..79] of the message schedule array
        for (let i = 0; i < 16; i++, offset += 4) {
            SHA512_W_H[i] = view.getUint32(offset);
            SHA512_W_L[i] = view.getUint32((offset += 4));
        }
        for (let i = 16; i < 80; i++) {
            // s0 := (w[i-15] rightrotate 1) xor (w[i-15] rightrotate 8) xor (w[i-15] rightshift 7)
            const W15h = SHA512_W_H[i - 15] | 0;
            const W15l = SHA512_W_L[i - 15] | 0;
            const s0h = _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSH(W15h, W15l, 1) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSH(W15h, W15l, 8) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.shrSH(W15h, W15l, 7);
            const s0l = _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSL(W15h, W15l, 1) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSL(W15h, W15l, 8) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.shrSL(W15h, W15l, 7);
            // s1 := (w[i-2] rightrotate 19) xor (w[i-2] rightrotate 61) xor (w[i-2] rightshift 6)
            const W2h = SHA512_W_H[i - 2] | 0;
            const W2l = SHA512_W_L[i - 2] | 0;
            const s1h = _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSH(W2h, W2l, 19) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrBH(W2h, W2l, 61) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.shrSH(W2h, W2l, 6);
            const s1l = _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSL(W2h, W2l, 19) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrBL(W2h, W2l, 61) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.shrSL(W2h, W2l, 6);
            // SHA256_W[i] = s0 + s1 + SHA256_W[i - 7] + SHA256_W[i - 16];
            const SUMl = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add4L(s0l, s1l, SHA512_W_L[i - 7], SHA512_W_L[i - 16]);
            const SUMh = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add4H(SUMl, s0h, s1h, SHA512_W_H[i - 7], SHA512_W_H[i - 16]);
            SHA512_W_H[i] = SUMh | 0;
            SHA512_W_L[i] = SUMl | 0;
        }
        let { Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl } = this;
        // Compression function main loop, 80 rounds
        for (let i = 0; i < 80; i++) {
            // S1 := (e rightrotate 14) xor (e rightrotate 18) xor (e rightrotate 41)
            const sigma1h = _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSH(Eh, El, 14) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSH(Eh, El, 18) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrBH(Eh, El, 41);
            const sigma1l = _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSL(Eh, El, 14) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSL(Eh, El, 18) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrBL(Eh, El, 41);
            //const T1 = (H + sigma1 + Chi(E, F, G) + SHA256_K[i] + SHA256_W[i]) | 0;
            const CHIh = (Eh & Fh) ^ (~Eh & Gh);
            const CHIl = (El & Fl) ^ (~El & Gl);
            // T1 = H + sigma1 + Chi(E, F, G) + SHA512_K[i] + SHA512_W[i]
            // prettier-ignore
            const T1ll = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add5L(Hl, sigma1l, CHIl, SHA512_Kl[i], SHA512_W_L[i]);
            const T1h = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add5H(T1ll, Hh, sigma1h, CHIh, SHA512_Kh[i], SHA512_W_H[i]);
            const T1l = T1ll | 0;
            // S0 := (a rightrotate 28) xor (a rightrotate 34) xor (a rightrotate 39)
            const sigma0h = _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSH(Ah, Al, 28) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrBH(Ah, Al, 34) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrBH(Ah, Al, 39);
            const sigma0l = _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrSL(Ah, Al, 28) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrBL(Ah, Al, 34) ^ _u64_js__WEBPACK_IMPORTED_MODULE_1__.rotrBL(Ah, Al, 39);
            const MAJh = (Ah & Bh) ^ (Ah & Ch) ^ (Bh & Ch);
            const MAJl = (Al & Bl) ^ (Al & Cl) ^ (Bl & Cl);
            Hh = Gh | 0;
            Hl = Gl | 0;
            Gh = Fh | 0;
            Gl = Fl | 0;
            Fh = Eh | 0;
            Fl = El | 0;
            ({ h: Eh, l: El } = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add(Dh | 0, Dl | 0, T1h | 0, T1l | 0));
            Dh = Ch | 0;
            Dl = Cl | 0;
            Ch = Bh | 0;
            Cl = Bl | 0;
            Bh = Ah | 0;
            Bl = Al | 0;
            const All = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add3L(T1l, sigma0l, MAJl);
            Ah = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add3H(All, T1h, sigma0h, MAJh);
            Al = All | 0;
        }
        // Add the compressed chunk to the current hash value
        ({ h: Ah, l: Al } = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add(this.Ah | 0, this.Al | 0, Ah | 0, Al | 0));
        ({ h: Bh, l: Bl } = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add(this.Bh | 0, this.Bl | 0, Bh | 0, Bl | 0));
        ({ h: Ch, l: Cl } = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add(this.Ch | 0, this.Cl | 0, Ch | 0, Cl | 0));
        ({ h: Dh, l: Dl } = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add(this.Dh | 0, this.Dl | 0, Dh | 0, Dl | 0));
        ({ h: Eh, l: El } = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add(this.Eh | 0, this.El | 0, Eh | 0, El | 0));
        ({ h: Fh, l: Fl } = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add(this.Fh | 0, this.Fl | 0, Fh | 0, Fl | 0));
        ({ h: Gh, l: Gl } = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add(this.Gh | 0, this.Gl | 0, Gh | 0, Gl | 0));
        ({ h: Hh, l: Hl } = _u64_js__WEBPACK_IMPORTED_MODULE_1__.add(this.Hh | 0, this.Hl | 0, Hh | 0, Hl | 0));
        this.set(Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl);
    }
    roundClean() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.clean)(SHA512_W_H, SHA512_W_L);
    }
    destroy() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.clean)(this.buffer);
        this.set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
class SHA384 extends SHA512 {
    constructor() {
        super(48);
        this.Ah = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[0] | 0;
        this.Al = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[1] | 0;
        this.Bh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[2] | 0;
        this.Bl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[3] | 0;
        this.Ch = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[4] | 0;
        this.Cl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[5] | 0;
        this.Dh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[6] | 0;
        this.Dl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[7] | 0;
        this.Eh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[8] | 0;
        this.El = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[9] | 0;
        this.Fh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[10] | 0;
        this.Fl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[11] | 0;
        this.Gh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[12] | 0;
        this.Gl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[13] | 0;
        this.Hh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[14] | 0;
        this.Hl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[15] | 0;
    }
}
/**
 * Truncated SHA512/256 and SHA512/224.
 * SHA512_IV is XORed with 0xa5a5a5a5a5a5a5a5, then used as "intermediary" IV of SHA512/t.
 * Then t hashes string to produce result IV.
 * See `test/misc/sha2-gen-iv.js`.
 */
/** SHA512/224 IV */
const T224_IV = /* @__PURE__ */ Uint32Array.from([
    0x8c3d37c8, 0x19544da2, 0x73e19966, 0x89dcd4d6, 0x1dfab7ae, 0x32ff9c82, 0x679dd514, 0x582f9fcf,
    0x0f6d2b69, 0x7bd44da8, 0x77e36f73, 0x04c48942, 0x3f9d85a8, 0x6a1d36c8, 0x1112e6ad, 0x91d692a1,
]);
/** SHA512/256 IV */
const T256_IV = /* @__PURE__ */ Uint32Array.from([
    0x22312194, 0xfc2bf72c, 0x9f555fa3, 0xc84c64c2, 0x2393b86b, 0x6f53b151, 0x96387719, 0x5940eabd,
    0x96283ee2, 0xa88effe3, 0xbe5e1e25, 0x53863992, 0x2b0199fc, 0x2c85b8aa, 0x0eb72ddc, 0x81c52ca2,
]);
class SHA512_224 extends SHA512 {
    constructor() {
        super(28);
        this.Ah = T224_IV[0] | 0;
        this.Al = T224_IV[1] | 0;
        this.Bh = T224_IV[2] | 0;
        this.Bl = T224_IV[3] | 0;
        this.Ch = T224_IV[4] | 0;
        this.Cl = T224_IV[5] | 0;
        this.Dh = T224_IV[6] | 0;
        this.Dl = T224_IV[7] | 0;
        this.Eh = T224_IV[8] | 0;
        this.El = T224_IV[9] | 0;
        this.Fh = T224_IV[10] | 0;
        this.Fl = T224_IV[11] | 0;
        this.Gh = T224_IV[12] | 0;
        this.Gl = T224_IV[13] | 0;
        this.Hh = T224_IV[14] | 0;
        this.Hl = T224_IV[15] | 0;
    }
}
class SHA512_256 extends SHA512 {
    constructor() {
        super(32);
        this.Ah = T256_IV[0] | 0;
        this.Al = T256_IV[1] | 0;
        this.Bh = T256_IV[2] | 0;
        this.Bl = T256_IV[3] | 0;
        this.Ch = T256_IV[4] | 0;
        this.Cl = T256_IV[5] | 0;
        this.Dh = T256_IV[6] | 0;
        this.Dl = T256_IV[7] | 0;
        this.Eh = T256_IV[8] | 0;
        this.El = T256_IV[9] | 0;
        this.Fh = T256_IV[10] | 0;
        this.Fl = T256_IV[11] | 0;
        this.Gh = T256_IV[12] | 0;
        this.Gl = T256_IV[13] | 0;
        this.Hh = T256_IV[14] | 0;
        this.Hl = T256_IV[15] | 0;
    }
}
/**
 * SHA2-256 hash function from RFC 4634.
 *
 * It is the fastest JS hash, even faster than Blake3.
 * To break sha256 using birthday attack, attackers need to try 2^128 hashes.
 * BTC network is doing 2^70 hashes/sec (2^95 hashes/year) as per 2025.
 */
const sha256 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_2__.createHasher)(() => new SHA256());
/** SHA2-224 hash function from RFC 4634 */
const sha224 = /* @__PURE__ */ (/* unused pure expression or super */ null && (createHasher(() => new SHA224())));
/** SHA2-512 hash function from RFC 4634. */
const sha512 = /* @__PURE__ */ (/* unused pure expression or super */ null && (createHasher(() => new SHA512())));
/** SHA2-384 hash function from RFC 4634. */
const sha384 = /* @__PURE__ */ (/* unused pure expression or super */ null && (createHasher(() => new SHA384())));
/**
 * SHA2-512/256 "truncated" hash function, with improved resistance to length extension attacks.
 * See the paper on [truncated SHA512](https://eprint.iacr.org/2010/548.pdf).
 */
const sha512_256 = /* @__PURE__ */ (/* unused pure expression or super */ null && (createHasher(() => new SHA512_256())));
/**
 * SHA2-512/224 "truncated" hash function, with improved resistance to length extension attacks.
 * See the paper on [truncated SHA512](https://eprint.iacr.org/2010/548.pdf).
 */
const sha512_224 = /* @__PURE__ */ (/* unused pure expression or super */ null && (createHasher(() => new SHA512_224())));
//# sourceMappingURL=sha2.js.map

/***/ },

/***/ 976
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Hash: () => (/* binding */ Hash),
/* harmony export */   abytes: () => (/* binding */ abytes),
/* harmony export */   aexists: () => (/* binding */ aexists),
/* harmony export */   ahash: () => (/* binding */ ahash),
/* harmony export */   anumber: () => (/* binding */ anumber),
/* harmony export */   aoutput: () => (/* binding */ aoutput),
/* harmony export */   asyncLoop: () => (/* binding */ asyncLoop),
/* harmony export */   bytesToHex: () => (/* binding */ bytesToHex),
/* harmony export */   checkOpts: () => (/* binding */ checkOpts),
/* harmony export */   clean: () => (/* binding */ clean),
/* harmony export */   concatBytes: () => (/* binding */ concatBytes),
/* harmony export */   createHasher: () => (/* binding */ createHasher),
/* harmony export */   createView: () => (/* binding */ createView),
/* harmony export */   hexToBytes: () => (/* binding */ hexToBytes),
/* harmony export */   isBytes: () => (/* binding */ isBytes),
/* harmony export */   kdfInputToBytes: () => (/* binding */ kdfInputToBytes),
/* harmony export */   randomBytes: () => (/* binding */ randomBytes),
/* harmony export */   rotl: () => (/* binding */ rotl),
/* harmony export */   rotr: () => (/* binding */ rotr),
/* harmony export */   swap32IfBE: () => (/* binding */ swap32IfBE),
/* harmony export */   toBytes: () => (/* binding */ toBytes),
/* harmony export */   u32: () => (/* binding */ u32),
/* harmony export */   utf8ToBytes: () => (/* binding */ utf8ToBytes)
/* harmony export */ });
/* unused harmony exports u8, isLE, byteSwap, swap8IfBE, byteSwapIfBE, byteSwap32, nextTick, bytesToUtf8, createOptHasher, createXOFer, wrapConstructor, wrapConstructorWithOpts, wrapXOFConstructorWithOpts */
/* harmony import */ var _noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(160);
/**
 * Utilities for hex, bytes, CSPRNG.
 * @module
 */
/*! noble-hashes - MIT License (c) 2022 Paul Miller (paulmillr.com) */
// We use WebCrypto aka globalThis.crypto, which exists in browsers and node.js 16+.
// node.js versions earlier than v19 don't declare it in global scope.
// For node.js, package.json#exports field mapping rewrites import
// from `crypto` to `cryptoNode`, which imports native module.
// Makes the utils un-importable in browsers without a bundler.
// Once node.js 18 is deprecated (2025-04-30), we can just drop the import.

/** Checks if something is Uint8Array. Be careful: nodejs Buffer will return true. */
function isBytes(a) {
    return a instanceof Uint8Array || (ArrayBuffer.isView(a) && a.constructor.name === 'Uint8Array');
}
/** Asserts something is positive integer. */
function anumber(n) {
    if (!Number.isSafeInteger(n) || n < 0)
        throw new Error('positive integer expected, got ' + n);
}
/** Asserts something is Uint8Array. */
function abytes(b, ...lengths) {
    if (!isBytes(b))
        throw new Error('Uint8Array expected');
    if (lengths.length > 0 && !lengths.includes(b.length))
        throw new Error('Uint8Array expected of length ' + lengths + ', got length=' + b.length);
}
/** Asserts something is hash */
function ahash(h) {
    if (typeof h !== 'function' || typeof h.create !== 'function')
        throw new Error('Hash should be wrapped by utils.createHasher');
    anumber(h.outputLen);
    anumber(h.blockLen);
}
/** Asserts a hash instance has not been destroyed / finished */
function aexists(instance, checkFinished = true) {
    if (instance.destroyed)
        throw new Error('Hash instance has been destroyed');
    if (checkFinished && instance.finished)
        throw new Error('Hash#digest() has already been called');
}
/** Asserts output is properly-sized byte array */
function aoutput(out, instance) {
    abytes(out);
    const min = instance.outputLen;
    if (out.length < min) {
        throw new Error('digestInto() expects output buffer of length at least ' + min);
    }
}
/** Cast u8 / u16 / u32 to u8. */
function u8(arr) {
    return new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength);
}
/** Cast u8 / u16 / u32 to u32. */
function u32(arr) {
    return new Uint32Array(arr.buffer, arr.byteOffset, Math.floor(arr.byteLength / 4));
}
/** Zeroize a byte array. Warning: JS provides no guarantees. */
function clean(...arrays) {
    for (let i = 0; i < arrays.length; i++) {
        arrays[i].fill(0);
    }
}
/** Create DataView of an array for easy byte-level manipulation. */
function createView(arr) {
    return new DataView(arr.buffer, arr.byteOffset, arr.byteLength);
}
/** The rotate right (circular right shift) operation for uint32 */
function rotr(word, shift) {
    return (word << (32 - shift)) | (word >>> shift);
}
/** The rotate left (circular left shift) operation for uint32 */
function rotl(word, shift) {
    return (word << shift) | ((word >>> (32 - shift)) >>> 0);
}
/** Is current platform little-endian? Most are. Big-Endian platform: IBM */
const isLE = /* @__PURE__ */ (() => new Uint8Array(new Uint32Array([0x11223344]).buffer)[0] === 0x44)();
/** The byte swap operation for uint32 */
function byteSwap(word) {
    return (((word << 24) & 0xff000000) |
        ((word << 8) & 0xff0000) |
        ((word >>> 8) & 0xff00) |
        ((word >>> 24) & 0xff));
}
/** Conditionally byte swap if on a big-endian platform */
const swap8IfBE = (/* unused pure expression or super */ null && (isLE
    ? (n) => n
    : (n) => byteSwap(n)));
/** @deprecated */
const byteSwapIfBE = (/* unused pure expression or super */ null && (swap8IfBE));
/** In place byte swap for Uint32Array */
function byteSwap32(arr) {
    for (let i = 0; i < arr.length; i++) {
        arr[i] = byteSwap(arr[i]);
    }
    return arr;
}
const swap32IfBE = isLE
    ? (u) => u
    : byteSwap32;
// Built-in hex conversion https://caniuse.com/mdn-javascript_builtins_uint8array_fromhex
const hasHexBuiltin = /* @__PURE__ */ (() => 
// @ts-ignore
typeof Uint8Array.from([]).toHex === 'function' && typeof Uint8Array.fromHex === 'function')();
// Array where index 0xf0 (240) is mapped to string 'f0'
const hexes = /* @__PURE__ */ Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, '0'));
/**
 * Convert byte array to hex string. Uses built-in function, when available.
 * @example bytesToHex(Uint8Array.from([0xca, 0xfe, 0x01, 0x23])) // 'cafe0123'
 */
function bytesToHex(bytes) {
    abytes(bytes);
    // @ts-ignore
    if (hasHexBuiltin)
        return bytes.toHex();
    // pre-caching improves the speed 6x
    let hex = '';
    for (let i = 0; i < bytes.length; i++) {
        hex += hexes[bytes[i]];
    }
    return hex;
}
// We use optimized technique to convert hex string to byte array
const asciis = { _0: 48, _9: 57, A: 65, F: 70, a: 97, f: 102 };
function asciiToBase16(ch) {
    if (ch >= asciis._0 && ch <= asciis._9)
        return ch - asciis._0; // '2' => 50-48
    if (ch >= asciis.A && ch <= asciis.F)
        return ch - (asciis.A - 10); // 'B' => 66-(65-10)
    if (ch >= asciis.a && ch <= asciis.f)
        return ch - (asciis.a - 10); // 'b' => 98-(97-10)
    return;
}
/**
 * Convert hex string to byte array. Uses built-in function, when available.
 * @example hexToBytes('cafe0123') // Uint8Array.from([0xca, 0xfe, 0x01, 0x23])
 */
function hexToBytes(hex) {
    if (typeof hex !== 'string')
        throw new Error('hex string expected, got ' + typeof hex);
    // @ts-ignore
    if (hasHexBuiltin)
        return Uint8Array.fromHex(hex);
    const hl = hex.length;
    const al = hl / 2;
    if (hl % 2)
        throw new Error('hex string expected, got unpadded hex of length ' + hl);
    const array = new Uint8Array(al);
    for (let ai = 0, hi = 0; ai < al; ai++, hi += 2) {
        const n1 = asciiToBase16(hex.charCodeAt(hi));
        const n2 = asciiToBase16(hex.charCodeAt(hi + 1));
        if (n1 === undefined || n2 === undefined) {
            const char = hex[hi] + hex[hi + 1];
            throw new Error('hex string expected, got non-hex character "' + char + '" at index ' + hi);
        }
        array[ai] = n1 * 16 + n2; // multiply first octet, e.g. 'a3' => 10*16+3 => 160 + 3 => 163
    }
    return array;
}
/**
 * There is no setImmediate in browser and setTimeout is slow.
 * Call of async fn will return Promise, which will be fullfiled only on
 * next scheduler queue processing step and this is exactly what we need.
 */
const nextTick = async () => { };
/** Returns control to thread each 'tick' ms to avoid blocking. */
async function asyncLoop(iters, tick, cb) {
    let ts = Date.now();
    for (let i = 0; i < iters; i++) {
        cb(i);
        // Date.now() is not monotonic, so in case if clock goes backwards we return return control too
        const diff = Date.now() - ts;
        if (diff >= 0 && diff < tick)
            continue;
        await nextTick();
        ts += diff;
    }
}
/**
 * Converts string to bytes using UTF8 encoding.
 * @example utf8ToBytes('abc') // Uint8Array.from([97, 98, 99])
 */
function utf8ToBytes(str) {
    if (typeof str !== 'string')
        throw new Error('string expected');
    return new Uint8Array(new TextEncoder().encode(str)); // https://bugzil.la/1681809
}
/**
 * Converts bytes to string using UTF8 encoding.
 * @example bytesToUtf8(Uint8Array.from([97, 98, 99])) // 'abc'
 */
function bytesToUtf8(bytes) {
    return new TextDecoder().decode(bytes);
}
/**
 * Normalizes (non-hex) string or Uint8Array to Uint8Array.
 * Warning: when Uint8Array is passed, it would NOT get copied.
 * Keep in mind for future mutable operations.
 */
function toBytes(data) {
    if (typeof data === 'string')
        data = utf8ToBytes(data);
    abytes(data);
    return data;
}
/**
 * Helper for KDFs: consumes uint8array or string.
 * When string is passed, does utf8 decoding, using TextDecoder.
 */
function kdfInputToBytes(data) {
    if (typeof data === 'string')
        data = utf8ToBytes(data);
    abytes(data);
    return data;
}
/** Copies several Uint8Arrays into one. */
function concatBytes(...arrays) {
    let sum = 0;
    for (let i = 0; i < arrays.length; i++) {
        const a = arrays[i];
        abytes(a);
        sum += a.length;
    }
    const res = new Uint8Array(sum);
    for (let i = 0, pad = 0; i < arrays.length; i++) {
        const a = arrays[i];
        res.set(a, pad);
        pad += a.length;
    }
    return res;
}
function checkOpts(defaults, opts) {
    if (opts !== undefined && {}.toString.call(opts) !== '[object Object]')
        throw new Error('options should be object or undefined');
    const merged = Object.assign(defaults, opts);
    return merged;
}
/** For runtime check if class implements interface */
class Hash {
}
/** Wraps hash function, creating an interface on top of it */
function createHasher(hashCons) {
    const hashC = (msg) => hashCons().update(toBytes(msg)).digest();
    const tmp = hashCons();
    hashC.outputLen = tmp.outputLen;
    hashC.blockLen = tmp.blockLen;
    hashC.create = () => hashCons();
    return hashC;
}
function createOptHasher(hashCons) {
    const hashC = (msg, opts) => hashCons(opts).update(toBytes(msg)).digest();
    const tmp = hashCons({});
    hashC.outputLen = tmp.outputLen;
    hashC.blockLen = tmp.blockLen;
    hashC.create = (opts) => hashCons(opts);
    return hashC;
}
function createXOFer(hashCons) {
    const hashC = (msg, opts) => hashCons(opts).update(toBytes(msg)).digest();
    const tmp = hashCons({});
    hashC.outputLen = tmp.outputLen;
    hashC.blockLen = tmp.blockLen;
    hashC.create = (opts) => hashCons(opts);
    return hashC;
}
const wrapConstructor = (/* unused pure expression or super */ null && (createHasher));
const wrapConstructorWithOpts = (/* unused pure expression or super */ null && (createOptHasher));
const wrapXOFConstructorWithOpts = (/* unused pure expression or super */ null && (createXOFer));
/** Cryptographically secure PRNG. Uses internal OS-level `crypto.getRandomValues`. */
function randomBytes(bytesLength = 32) {
    if (_noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto && typeof _noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto.getRandomValues === 'function') {
        return _noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto.getRandomValues(new Uint8Array(bytesLength));
    }
    // Legacy Node.js compatibility
    if (_noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto && typeof _noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto.randomBytes === 'function') {
        return Uint8Array.from(_noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto.randomBytes(bytesLength));
    }
    throw new Error('crypto.getRandomValues must be defined');
}
//# sourceMappingURL=utils.js.map

/***/ },

/***/ 324
(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {

/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   base64: () => (/* binding */ base64)
/* harmony export */ });
/* unused harmony exports utils, base16, base32, base32nopad, base32hex, base32hexnopad, base32crockford, base64nopad, base64url, base64urlnopad, base58, base58flickr, base58xrp, base58xmr, createBase58check, base58check, bech32, bech32m, utf8, hex, bytesToString, str, stringToBytes, bytes */
/*! scure-base - MIT License (c) 2022 Paul Miller (paulmillr.com) */
function isBytes(a) {
    return a instanceof Uint8Array || (ArrayBuffer.isView(a) && a.constructor.name === 'Uint8Array');
}
/** Asserts something is Uint8Array. */
function abytes(b, ...lengths) {
    if (!isBytes(b))
        throw new Error('Uint8Array expected');
    if (lengths.length > 0 && !lengths.includes(b.length))
        throw new Error('Uint8Array expected of length ' + lengths + ', got length=' + b.length);
}
function isArrayOf(isString, arr) {
    if (!Array.isArray(arr))
        return false;
    if (arr.length === 0)
        return true;
    if (isString) {
        return arr.every((item) => typeof item === 'string');
    }
    else {
        return arr.every((item) => Number.isSafeInteger(item));
    }
}
// no abytes: seems to have 10% slowdown. Why?!
function afn(input) {
    if (typeof input !== 'function')
        throw new Error('function expected');
    return true;
}
function astr(label, input) {
    if (typeof input !== 'string')
        throw new Error(`${label}: string expected`);
    return true;
}
function anumber(n) {
    if (!Number.isSafeInteger(n))
        throw new Error(`invalid integer: ${n}`);
}
function aArr(input) {
    if (!Array.isArray(input))
        throw new Error('array expected');
}
function astrArr(label, input) {
    if (!isArrayOf(true, input))
        throw new Error(`${label}: array of strings expected`);
}
function anumArr(label, input) {
    if (!isArrayOf(false, input))
        throw new Error(`${label}: array of numbers expected`);
}
/**
 * @__NO_SIDE_EFFECTS__
 */
function chain(...args) {
    const id = (a) => a;
    // Wrap call in closure so JIT can inline calls
    const wrap = (a, b) => (c) => a(b(c));
    // Construct chain of args[-1].encode(args[-2].encode([...]))
    const encode = args.map((x) => x.encode).reduceRight(wrap, id);
    // Construct chain of args[0].decode(args[1].decode(...))
    const decode = args.map((x) => x.decode).reduce(wrap, id);
    return { encode, decode };
}
/**
 * Encodes integer radix representation to array of strings using alphabet and back.
 * Could also be array of strings.
 * @__NO_SIDE_EFFECTS__
 */
function alphabet(letters) {
    // mapping 1 to "b"
    const lettersA = typeof letters === 'string' ? letters.split('') : letters;
    const len = lettersA.length;
    astrArr('alphabet', lettersA);
    // mapping "b" to 1
    const indexes = new Map(lettersA.map((l, i) => [l, i]));
    return {
        encode: (digits) => {
            aArr(digits);
            return digits.map((i) => {
                if (!Number.isSafeInteger(i) || i < 0 || i >= len)
                    throw new Error(`alphabet.encode: digit index outside alphabet "${i}". Allowed: ${letters}`);
                return lettersA[i];
            });
        },
        decode: (input) => {
            aArr(input);
            return input.map((letter) => {
                astr('alphabet.decode', letter);
                const i = indexes.get(letter);
                if (i === undefined)
                    throw new Error(`Unknown letter: "${letter}". Allowed: ${letters}`);
                return i;
            });
        },
    };
}
/**
 * @__NO_SIDE_EFFECTS__
 */
function join(separator = '') {
    astr('join', separator);
    return {
        encode: (from) => {
            astrArr('join.decode', from);
            return from.join(separator);
        },
        decode: (to) => {
            astr('join.decode', to);
            return to.split(separator);
        },
    };
}
/**
 * Pad strings array so it has integer number of bits
 * @__NO_SIDE_EFFECTS__
 */
function padding(bits, chr = '=') {
    anumber(bits);
    astr('padding', chr);
    return {
        encode(data) {
            astrArr('padding.encode', data);
            while ((data.length * bits) % 8)
                data.push(chr);
            return data;
        },
        decode(input) {
            astrArr('padding.decode', input);
            let end = input.length;
            if ((end * bits) % 8)
                throw new Error('padding: invalid, string should have whole number of bytes');
            for (; end > 0 && input[end - 1] === chr; end--) {
                const last = end - 1;
                const byte = last * bits;
                if (byte % 8 === 0)
                    throw new Error('padding: invalid, string has too much padding');
            }
            return input.slice(0, end);
        },
    };
}
/**
 * @__NO_SIDE_EFFECTS__
 */
function normalize(fn) {
    afn(fn);
    return { encode: (from) => from, decode: (to) => fn(to) };
}
/**
 * Slow: O(n^2) time complexity
 */
function convertRadix(data, from, to) {
    // base 1 is impossible
    if (from < 2)
        throw new Error(`convertRadix: invalid from=${from}, base cannot be less than 2`);
    if (to < 2)
        throw new Error(`convertRadix: invalid to=${to}, base cannot be less than 2`);
    aArr(data);
    if (!data.length)
        return [];
    let pos = 0;
    const res = [];
    const digits = Array.from(data, (d) => {
        anumber(d);
        if (d < 0 || d >= from)
            throw new Error(`invalid integer: ${d}`);
        return d;
    });
    const dlen = digits.length;
    while (true) {
        let carry = 0;
        let done = true;
        for (let i = pos; i < dlen; i++) {
            const digit = digits[i];
            const fromCarry = from * carry;
            const digitBase = fromCarry + digit;
            if (!Number.isSafeInteger(digitBase) ||
                fromCarry / from !== carry ||
                digitBase - digit !== fromCarry) {
                throw new Error('convertRadix: carry overflow');
            }
            const div = digitBase / to;
            carry = digitBase % to;
            const rounded = Math.floor(div);
            digits[i] = rounded;
            if (!Number.isSafeInteger(rounded) || rounded * to + carry !== digitBase)
                throw new Error('convertRadix: carry overflow');
            if (!done)
                continue;
            else if (!rounded)
                pos = i;
            else
                done = false;
        }
        res.push(carry);
        if (done)
            break;
    }
    for (let i = 0; i < data.length - 1 && data[i] === 0; i++)
        res.push(0);
    return res.reverse();
}
const gcd = (a, b) => (b === 0 ? a : gcd(b, a % b));
const radix2carry = /* @__NO_SIDE_EFFECTS__ */ (from, to) => from + (to - gcd(from, to));
const powers = /* @__PURE__ */ (() => {
    let res = [];
    for (let i = 0; i < 40; i++)
        res.push(2 ** i);
    return res;
})();
/**
 * Implemented with numbers, because BigInt is 5x slower
 */
function convertRadix2(data, from, to, padding) {
    aArr(data);
    if (from <= 0 || from > 32)
        throw new Error(`convertRadix2: wrong from=${from}`);
    if (to <= 0 || to > 32)
        throw new Error(`convertRadix2: wrong to=${to}`);
    if (radix2carry(from, to) > 32) {
        throw new Error(`convertRadix2: carry overflow from=${from} to=${to} carryBits=${radix2carry(from, to)}`);
    }
    let carry = 0;
    let pos = 0; // bitwise position in current element
    const max = powers[from];
    const mask = powers[to] - 1;
    const res = [];
    for (const n of data) {
        anumber(n);
        if (n >= max)
            throw new Error(`convertRadix2: invalid data word=${n} from=${from}`);
        carry = (carry << from) | n;
        if (pos + from > 32)
            throw new Error(`convertRadix2: carry overflow pos=${pos} from=${from}`);
        pos += from;
        for (; pos >= to; pos -= to)
            res.push(((carry >> (pos - to)) & mask) >>> 0);
        const pow = powers[pos];
        if (pow === undefined)
            throw new Error('invalid carry');
        carry &= pow - 1; // clean carry, otherwise it will cause overflow
    }
    carry = (carry << (to - pos)) & mask;
    if (!padding && pos >= from)
        throw new Error('Excess padding');
    if (!padding && carry > 0)
        throw new Error(`Non-zero padding: ${carry}`);
    if (padding && pos > 0)
        res.push(carry >>> 0);
    return res;
}
/**
 * @__NO_SIDE_EFFECTS__
 */
function radix(num) {
    anumber(num);
    const _256 = 2 ** 8;
    return {
        encode: (bytes) => {
            if (!isBytes(bytes))
                throw new Error('radix.encode input should be Uint8Array');
            return convertRadix(Array.from(bytes), _256, num);
        },
        decode: (digits) => {
            anumArr('radix.decode', digits);
            return Uint8Array.from(convertRadix(digits, num, _256));
        },
    };
}
/**
 * If both bases are power of same number (like `2**8 <-> 2**64`),
 * there is a linear algorithm. For now we have implementation for power-of-two bases only.
 * @__NO_SIDE_EFFECTS__
 */
function radix2(bits, revPadding = false) {
    anumber(bits);
    if (bits <= 0 || bits > 32)
        throw new Error('radix2: bits should be in (0..32]');
    if (radix2carry(8, bits) > 32 || radix2carry(bits, 8) > 32)
        throw new Error('radix2: carry overflow');
    return {
        encode: (bytes) => {
            if (!isBytes(bytes))
                throw new Error('radix2.encode input should be Uint8Array');
            return convertRadix2(Array.from(bytes), 8, bits, !revPadding);
        },
        decode: (digits) => {
            anumArr('radix2.decode', digits);
            return Uint8Array.from(convertRadix2(digits, bits, 8, revPadding));
        },
    };
}
function unsafeWrapper(fn) {
    afn(fn);
    return function (...args) {
        try {
            return fn.apply(null, args);
        }
        catch (e) { }
    };
}
function checksum(len, fn) {
    anumber(len);
    afn(fn);
    return {
        encode(data) {
            if (!isBytes(data))
                throw new Error('checksum.encode: input should be Uint8Array');
            const sum = fn(data).slice(0, len);
            const res = new Uint8Array(data.length + len);
            res.set(data);
            res.set(sum, data.length);
            return res;
        },
        decode(data) {
            if (!isBytes(data))
                throw new Error('checksum.decode: input should be Uint8Array');
            const payload = data.slice(0, -len);
            const oldChecksum = data.slice(-len);
            const newChecksum = fn(payload).slice(0, len);
            for (let i = 0; i < len; i++)
                if (newChecksum[i] !== oldChecksum[i])
                    throw new Error('Invalid checksum');
            return payload;
        },
    };
}
// prettier-ignore
const utils = {
    alphabet, chain, checksum, convertRadix, convertRadix2, radix, radix2, join, padding,
};
// RFC 4648 aka RFC 3548
// ---------------------
/**
 * base16 encoding from RFC 4648.
 * @example
 * ```js
 * base16.encode(Uint8Array.from([0x12, 0xab]));
 * // => '12AB'
 * ```
 */
const base16 = chain(radix2(4), alphabet('0123456789ABCDEF'), join(''));
/**
 * base32 encoding from RFC 4648. Has padding.
 * Use `base32nopad` for unpadded version.
 * Also check out `base32hex`, `base32hexnopad`, `base32crockford`.
 * @example
 * ```js
 * base32.encode(Uint8Array.from([0x12, 0xab]));
 * // => 'CKVQ===='
 * base32.decode('CKVQ====');
 * // => Uint8Array.from([0x12, 0xab])
 * ```
 */
const base32 = chain(radix2(5), alphabet('ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'), padding(5), join(''));
/**
 * base32 encoding from RFC 4648. No padding.
 * Use `base32` for padded version.
 * Also check out `base32hex`, `base32hexnopad`, `base32crockford`.
 * @example
 * ```js
 * base32nopad.encode(Uint8Array.from([0x12, 0xab]));
 * // => 'CKVQ'
 * base32nopad.decode('CKVQ');
 * // => Uint8Array.from([0x12, 0xab])
 * ```
 */
const base32nopad = chain(radix2(5), alphabet('ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'), join(''));
/**
 * base32 encoding from RFC 4648. Padded. Compared to ordinary `base32`, slightly different alphabet.
 * Use `base32hexnopad` for unpadded version.
 * @example
 * ```js
 * base32hex.encode(Uint8Array.from([0x12, 0xab]));
 * // => '2ALG===='
 * base32hex.decode('2ALG====');
 * // => Uint8Array.from([0x12, 0xab])
 * ```
 */
const base32hex = chain(radix2(5), alphabet('0123456789ABCDEFGHIJKLMNOPQRSTUV'), padding(5), join(''));
/**
 * base32 encoding from RFC 4648. No padding. Compared to ordinary `base32`, slightly different alphabet.
 * Use `base32hex` for padded version.
 * @example
 * ```js
 * base32hexnopad.encode(Uint8Array.from([0x12, 0xab]));
 * // => '2ALG'
 * base32hexnopad.decode('2ALG');
 * // => Uint8Array.from([0x12, 0xab])
 * ```
 */
const base32hexnopad = chain(radix2(5), alphabet('0123456789ABCDEFGHIJKLMNOPQRSTUV'), join(''));
/**
 * base32 encoding from RFC 4648. Doug Crockford's version.
 * https://www.crockford.com/base32.html
 * @example
 * ```js
 * base32crockford.encode(Uint8Array.from([0x12, 0xab]));
 * // => '2ANG'
 * base32crockford.decode('2ANG');
 * // => Uint8Array.from([0x12, 0xab])
 * ```
 */
const base32crockford = chain(radix2(5), alphabet('0123456789ABCDEFGHJKMNPQRSTVWXYZ'), join(''), normalize((s) => s.toUpperCase().replace(/O/g, '0').replace(/[IL]/g, '1')));
// Built-in base64 conversion https://caniuse.com/mdn-javascript_builtins_uint8array_frombase64
// prettier-ignore
const hasBase64Builtin = /* @__PURE__ */ (() => typeof Uint8Array.from([]).toBase64 === 'function' &&
    typeof Uint8Array.fromBase64 === 'function')();
const decodeBase64Builtin = (s, isUrl) => {
    astr('base64', s);
    const re = isUrl ? /^[A-Za-z0-9=_-]+$/ : /^[A-Za-z0-9=+/]+$/;
    const alphabet = isUrl ? 'base64url' : 'base64';
    if (s.length > 0 && !re.test(s))
        throw new Error('invalid base64');
    return Uint8Array.fromBase64(s, { alphabet, lastChunkHandling: 'strict' });
};
/**
 * base64 from RFC 4648. Padded.
 * Use `base64nopad` for unpadded version.
 * Also check out `base64url`, `base64urlnopad`.
 * Falls back to built-in function, when available.
 * @example
 * ```js
 * base64.encode(Uint8Array.from([0x12, 0xab]));
 * // => 'Eqs='
 * base64.decode('Eqs=');
 * // => Uint8Array.from([0x12, 0xab])
 * ```
 */
// prettier-ignore
const base64 = hasBase64Builtin ? {
    encode(b) { abytes(b); return b.toBase64(); },
    decode(s) { return decodeBase64Builtin(s, false); },
} : chain(radix2(6), alphabet('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'), padding(6), join(''));
/**
 * base64 from RFC 4648. No padding.
 * Use `base64` for padded version.
 * @example
 * ```js
 * base64nopad.encode(Uint8Array.from([0x12, 0xab]));
 * // => 'Eqs'
 * base64nopad.decode('Eqs');
 * // => Uint8Array.from([0x12, 0xab])
 * ```
 */
const base64nopad = chain(radix2(6), alphabet('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'), join(''));
/**
 * base64 from RFC 4648, using URL-safe alphabet. Padded.
 * Use `base64urlnopad` for unpadded version.
 * Falls back to built-in function, when available.
 * @example
 * ```js
 * base64url.encode(Uint8Array.from([0x12, 0xab]));
 * // => 'Eqs='
 * base64url.decode('Eqs=');
 * // => Uint8Array.from([0x12, 0xab])
 * ```
 */
// prettier-ignore
const base64url = hasBase64Builtin ? {
    encode(b) { abytes(b); return b.toBase64({ alphabet: 'base64url' }); },
    decode(s) { return decodeBase64Builtin(s, true); },
} : chain(radix2(6), alphabet('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'), padding(6), join(''));
/**
 * base64 from RFC 4648, using URL-safe alphabet. No padding.
 * Use `base64url` for padded version.
 * @example
 * ```js
 * base64urlnopad.encode(Uint8Array.from([0x12, 0xab]));
 * // => 'Eqs'
 * base64urlnopad.decode('Eqs');
 * // => Uint8Array.from([0x12, 0xab])
 * ```
 */
const base64urlnopad = chain(radix2(6), alphabet('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'), join(''));
// base58 code
// -----------
const genBase58 = /* @__NO_SIDE_EFFECTS__ */ (abc) => chain(radix(58), alphabet(abc), join(''));
/**
 * base58: base64 without ambigous characters +, /, 0, O, I, l.
 * Quadratic (O(n^2)) - so, can't be used on large inputs.
 * @example
 * ```js
 * base58.decode('01abcdef');
 * // => '3UhJW'
 * ```
 */
const base58 = genBase58('123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz');
/**
 * base58: flickr version. Check out `base58`.
 */
const base58flickr = genBase58('123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ');
/**
 * base58: XRP version. Check out `base58`.
 */
const base58xrp = genBase58('rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz');
// Data len (index) -> encoded block len
const XMR_BLOCK_LEN = [0, 2, 3, 5, 6, 7, 9, 10, 11];
/**
 * base58: XMR version. Check out `base58`.
 * Done in 8-byte blocks (which equals 11 chars in decoding). Last (non-full) block padded with '1' to size in XMR_BLOCK_LEN.
 * Block encoding significantly reduces quadratic complexity of base58.
 */
const base58xmr = {
    encode(data) {
        let res = '';
        for (let i = 0; i < data.length; i += 8) {
            const block = data.subarray(i, i + 8);
            res += base58.encode(block).padStart(XMR_BLOCK_LEN[block.length], '1');
        }
        return res;
    },
    decode(str) {
        let res = [];
        for (let i = 0; i < str.length; i += 11) {
            const slice = str.slice(i, i + 11);
            const blockLen = XMR_BLOCK_LEN.indexOf(slice.length);
            const block = base58.decode(slice);
            for (let j = 0; j < block.length - blockLen; j++) {
                if (block[j] !== 0)
                    throw new Error('base58xmr: wrong padding');
            }
            res = res.concat(Array.from(block.slice(block.length - blockLen)));
        }
        return Uint8Array.from(res);
    },
};
/**
 * Method, which creates base58check encoder.
 * Requires function, calculating sha256.
 */
const createBase58check = (sha256) => chain(checksum(4, (data) => sha256(sha256(data))), base58);
/**
 * Use `createBase58check` instead.
 * @deprecated
 */
const base58check = (/* unused pure expression or super */ null && (createBase58check));
const BECH_ALPHABET = chain(alphabet('qpzry9x8gf2tvdw0s3jn54khce6mua7l'), join(''));
const POLYMOD_GENERATORS = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];
function bech32Polymod(pre) {
    const b = pre >> 25;
    let chk = (pre & 0x1ffffff) << 5;
    for (let i = 0; i < POLYMOD_GENERATORS.length; i++) {
        if (((b >> i) & 1) === 1)
            chk ^= POLYMOD_GENERATORS[i];
    }
    return chk;
}
function bechChecksum(prefix, words, encodingConst = 1) {
    const len = prefix.length;
    let chk = 1;
    for (let i = 0; i < len; i++) {
        const c = prefix.charCodeAt(i);
        if (c < 33 || c > 126)
            throw new Error(`Invalid prefix (${prefix})`);
        chk = bech32Polymod(chk) ^ (c >> 5);
    }
    chk = bech32Polymod(chk);
    for (let i = 0; i < len; i++)
        chk = bech32Polymod(chk) ^ (prefix.charCodeAt(i) & 0x1f);
    for (let v of words)
        chk = bech32Polymod(chk) ^ v;
    for (let i = 0; i < 6; i++)
        chk = bech32Polymod(chk);
    chk ^= encodingConst;
    return BECH_ALPHABET.encode(convertRadix2([chk % powers[30]], 30, 5, false));
}
/**
 * @__NO_SIDE_EFFECTS__
 */
function genBech32(encoding) {
    const ENCODING_CONST = encoding === 'bech32' ? 1 : 0x2bc830a3;
    const _words = radix2(5);
    const fromWords = _words.decode;
    const toWords = _words.encode;
    const fromWordsUnsafe = unsafeWrapper(fromWords);
    function encode(prefix, words, limit = 90) {
        astr('bech32.encode prefix', prefix);
        if (isBytes(words))
            words = Array.from(words);
        anumArr('bech32.encode', words);
        const plen = prefix.length;
        if (plen === 0)
            throw new TypeError(`Invalid prefix length ${plen}`);
        const actualLength = plen + 7 + words.length;
        if (limit !== false && actualLength > limit)
            throw new TypeError(`Length ${actualLength} exceeds limit ${limit}`);
        const lowered = prefix.toLowerCase();
        const sum = bechChecksum(lowered, words, ENCODING_CONST);
        return `${lowered}1${BECH_ALPHABET.encode(words)}${sum}`;
    }
    function decode(str, limit = 90) {
        astr('bech32.decode input', str);
        const slen = str.length;
        if (slen < 8 || (limit !== false && slen > limit))
            throw new TypeError(`invalid string length: ${slen} (${str}). Expected (8..${limit})`);
        // don't allow mixed case
        const lowered = str.toLowerCase();
        if (str !== lowered && str !== str.toUpperCase())
            throw new Error(`String must be lowercase or uppercase`);
        const sepIndex = lowered.lastIndexOf('1');
        if (sepIndex === 0 || sepIndex === -1)
            throw new Error(`Letter "1" must be present between prefix and data only`);
        const prefix = lowered.slice(0, sepIndex);
        const data = lowered.slice(sepIndex + 1);
        if (data.length < 6)
            throw new Error('Data must be at least 6 characters long');
        const words = BECH_ALPHABET.decode(data).slice(0, -6);
        const sum = bechChecksum(prefix, words, ENCODING_CONST);
        if (!data.endsWith(sum))
            throw new Error(`Invalid checksum in ${str}: expected "${sum}"`);
        return { prefix, words };
    }
    const decodeUnsafe = unsafeWrapper(decode);
    function decodeToBytes(str) {
        const { prefix, words } = decode(str, false);
        return { prefix, words, bytes: fromWords(words) };
    }
    function encodeFromBytes(prefix, bytes) {
        return encode(prefix, toWords(bytes));
    }
    return {
        encode,
        decode,
        encodeFromBytes,
        decodeToBytes,
        decodeUnsafe,
        fromWords,
        fromWordsUnsafe,
        toWords,
    };
}
/**
 * bech32 from BIP 173. Operates on words.
 * For high-level, check out scure-btc-signer:
 * https://github.com/paulmillr/scure-btc-signer.
 */
const bech32 = genBech32('bech32');
/**
 * bech32m from BIP 350. Operates on words.
 * It was to mitigate `bech32` weaknesses.
 * For high-level, check out scure-btc-signer:
 * https://github.com/paulmillr/scure-btc-signer.
 */
const bech32m = genBech32('bech32m');
/**
 * UTF-8-to-byte decoder. Uses built-in TextDecoder / TextEncoder.
 * @example
 * ```js
 * const b = utf8.decode("hey"); // => new Uint8Array([ 104, 101, 121 ])
 * const str = utf8.encode(b); // "hey"
 * ```
 */
const utf8 = {
    encode: (data) => new TextDecoder().decode(data),
    decode: (str) => new TextEncoder().encode(str),
};
// Built-in hex conversion https://caniuse.com/mdn-javascript_builtins_uint8array_fromhex
// prettier-ignore
const hasHexBuiltin = /* @__PURE__ */ (() => typeof Uint8Array.from([]).toHex === 'function' &&
    typeof Uint8Array.fromHex === 'function')();
// prettier-ignore
const hexBuiltin = {
    encode(data) { abytes(data); return data.toHex(); },
    decode(s) { astr('hex', s); return Uint8Array.fromHex(s); },
};
/**
 * hex string decoder. Uses built-in function, when available.
 * @example
 * ```js
 * const b = hex.decode("0102ff"); // => new Uint8Array([ 1, 2, 255 ])
 * const str = hex.encode(b); // "0102ff"
 * ```
 */
const hex = hasHexBuiltin
    ? hexBuiltin
    : chain(radix2(4), alphabet('0123456789abcdef'), join(''), normalize((s) => {
        if (typeof s !== 'string' || s.length % 2 !== 0)
            throw new TypeError(`hex.decode: expected string, got ${typeof s} with length ${s.length}`);
        return s.toLowerCase();
    }));
// prettier-ignore
const CODERS = {
    utf8, hex, base16, base32, base64, base64url, base58, base58xmr
};
const coderTypeError = 'Invalid encoding type. Available types: utf8, hex, base16, base32, base64, base64url, base58, base58xmr';
/** @deprecated */
const bytesToString = (type, bytes) => {
    if (typeof type !== 'string' || !CODERS.hasOwnProperty(type))
        throw new TypeError(coderTypeError);
    if (!isBytes(bytes))
        throw new TypeError('bytesToString() expects Uint8Array');
    return CODERS[type].encode(bytes);
};
/** @deprecated */
const str = (/* unused pure expression or super */ null && (bytesToString)); // as in python, but for bytes only
/** @deprecated */
const stringToBytes = (type, str) => {
    if (!CODERS.hasOwnProperty(type))
        throw new TypeError(coderTypeError);
    if (typeof str !== 'string')
        throw new TypeError('stringToBytes() expects string');
    return CODERS[type].decode(str);
};
/** @deprecated */
const bytes = (/* unused pure expression or super */ null && (stringToBytes));
//# sourceMappingURL=index.js.map

/***/ }

/******/ });
/************************************************************************/
/******/ // The module cache
/******/ var __webpack_module_cache__ = {};
/******/ 
/******/ // The require function
/******/ function __webpack_require__(moduleId) {
/******/ 	// Check if module is in cache
/******/ 	var cachedModule = __webpack_module_cache__[moduleId];
/******/ 	if (cachedModule !== undefined) {
/******/ 		return cachedModule.exports;
/******/ 	}
/******/ 	// Create a new module (and put it into the cache)
/******/ 	var module = __webpack_module_cache__[moduleId] = {
/******/ 		// no module.id needed
/******/ 		// no module.loaded needed
/******/ 		exports: {}
/******/ 	};
/******/ 
/******/ 	// Execute the module function
/******/ 	__webpack_modules__[moduleId](module, module.exports, __webpack_require__);
/******/ 
/******/ 	// Return the exports of the module
/******/ 	return module.exports;
/******/ }
/******/ 
/************************************************************************/
/******/ /* webpack/runtime/define property getters */
/******/ (() => {
/******/ 	// define getter functions for harmony exports
/******/ 	__webpack_require__.d = (exports, definition) => {
/******/ 		for(var key in definition) {
/******/ 			if(__webpack_require__.o(definition, key) && !__webpack_require__.o(exports, key)) {
/******/ 				Object.defineProperty(exports, key, { enumerable: true, get: definition[key] });
/******/ 			}
/******/ 		}
/******/ 	};
/******/ })();
/******/ 
/******/ /* webpack/runtime/global */
/******/ (() => {
/******/ 	__webpack_require__.g = (function() {
/******/ 		if (typeof globalThis === 'object') return globalThis;
/******/ 		try {
/******/ 			return this || new Function('return this')();
/******/ 		} catch (e) {
/******/ 			if (typeof window === 'object') return window;
/******/ 		}
/******/ 	})();
/******/ })();
/******/ 
/******/ /* webpack/runtime/hasOwnProperty shorthand */
/******/ (() => {
/******/ 	__webpack_require__.o = (obj, prop) => (Object.prototype.hasOwnProperty.call(obj, prop))
/******/ })();
/******/ 
/************************************************************************/
var __webpack_exports__ = {};
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   _bw: () => (/* binding */ _bw),
/* harmony export */   aes256cbc: () => (/* binding */ aes256cbc),
/* harmony export */   aes256cbcBuffer: () => (/* binding */ aes256cbcBuffer),
/* harmony export */   base64decode: () => (/* binding */ base64decode),
/* harmony export */   base64decodeBuffer: () => (/* binding */ base64decodeBuffer),
/* harmony export */   base64encode: () => (/* binding */ base64encode),
/* harmony export */   base64encodeBuffer: () => (/* binding */ base64encodeBuffer),
/* harmony export */   callFunction: () => (/* binding */ callFunction),
/* harmony export */   callFunctionPromise: () => (/* binding */ callFunctionPromise),
/* harmony export */   canCallFunction: () => (/* binding */ canCallFunction),
/* harmony export */   canCallFunctionPromise: () => (/* binding */ canCallFunctionPromise),
/* harmony export */   chacha20: () => (/* binding */ chacha20),
/* harmony export */   chacha20Buffer: () => (/* binding */ chacha20Buffer),
/* harmony export */   copyHttpResponseBody: () => (/* binding */ copyHttpResponseBody),
/* harmony export */   delayPromise: () => (/* binding */ delayPromise),
/* harmony export */   eventQueueDispose: () => (/* binding */ eventQueueDispose),
/* harmony export */   eventQueueWaitPromise: () => (/* binding */ eventQueueWaitPromise),
/* harmony export */   fetchAsync: () => (/* binding */ fetchAsync),
/* harmony export */   fetchBufferAsync: () => (/* binding */ fetchBufferAsync),
/* harmony export */   fetchBufferPromise: () => (/* binding */ fetchBufferPromise),
/* harmony export */   fetchPromise: () => (/* binding */ fetchPromise),
/* harmony export */   fetchStreamAsync: () => (/* binding */ fetchStreamAsync),
/* harmony export */   fetchStreamPromise: () => (/* binding */ fetchStreamPromise),
/* harmony export */   fromJSON: () => (/* binding */ fromJSON),
/* harmony export */   genPubKey: () => (/* binding */ genPubKey),
/* harmony export */   genPubKeyBuffer: () => (/* binding */ genPubKeyBuffer),
/* harmony export */   generatePrivateKey: () => (/* binding */ generatePrivateKey),
/* harmony export */   generatePrivateKeyBuffer: () => (/* binding */ generatePrivateKeyBuffer),
/* harmony export */   getBundledResource: () => (/* binding */ getBundledResource),
/* harmony export */   getClipboardContentAsync: () => (/* binding */ getClipboardContentAsync),
/* harmony export */   getClipboardContentPromise: () => (/* binding */ getClipboardContentPromise),
/* harmony export */   getPlatformName: () => (/* binding */ getPlatformName),
/* harmony export */   getPromise: () => (/* binding */ getPromise),
/* harmony export */   getRuntimeName: () => (/* binding */ getRuntimeName),
/* harmony export */   hasBundledResource: () => (/* binding */ hasBundledResource),
/* harmony export */   hkdfExpandBuffer: () => (/* binding */ hkdfExpandBuffer),
/* harmony export */   hkdfExtractBuffer: () => (/* binding */ hkdfExtractBuffer),
/* harmony export */   hkdf_expand: () => (/* binding */ hkdf_expand),
/* harmony export */   hkdf_extract: () => (/* binding */ hkdf_extract),
/* harmony export */   hmac: () => (/* binding */ hmac),
/* harmony export */   hmacBuffer: () => (/* binding */ hmacBuffer),
/* harmony export */   httpResponseBodyLength: () => (/* binding */ httpResponseBodyLength),
/* harmony export */   newPromise: () => (/* binding */ newPromise),
/* harmony export */   nfkc: () => (/* binding */ nfkc),
/* harmony export */   openURL: () => (/* binding */ openURL),
/* harmony export */   panic: () => (/* binding */ panic),
/* harmony export */   randomBytes: () => (/* binding */ randomBytes),
/* harmony export */   randomBytesBuffer: () => (/* binding */ randomBytesBuffer),
/* harmony export */   rejectPromise: () => (/* binding */ rejectPromise),
/* harmony export */   resolvePromise: () => (/* binding */ resolvePromise),
/* harmony export */   rtcAddIceCandidateAsync: () => (/* binding */ rtcAddIceCandidateAsync),
/* harmony export */   rtcAddIceCandidatePromise: () => (/* binding */ rtcAddIceCandidatePromise),
/* harmony export */   rtcCreateAnswerAsync: () => (/* binding */ rtcCreateAnswerAsync),
/* harmony export */   rtcCreateAnswerPromise: () => (/* binding */ rtcCreateAnswerPromise),
/* harmony export */   rtcCreateDataChannel: () => (/* binding */ rtcCreateDataChannel),
/* harmony export */   rtcCreateIceCandidate: () => (/* binding */ rtcCreateIceCandidate),
/* harmony export */   rtcCreateOfferAsync: () => (/* binding */ rtcCreateOfferAsync),
/* harmony export */   rtcCreateOfferPromise: () => (/* binding */ rtcCreateOfferPromise),
/* harmony export */   rtcCreatePeerConnection: () => (/* binding */ rtcCreatePeerConnection),
/* harmony export */   rtcDataChannelConsumeEvent: () => (/* binding */ rtcDataChannelConsumeEvent),
/* harmony export */   rtcDataChannelEventBinaryLength: () => (/* binding */ rtcDataChannelEventBinaryLength),
/* harmony export */   rtcDataChannelEventError: () => (/* binding */ rtcDataChannelEventError),
/* harmony export */   rtcDataChannelEventType: () => (/* binding */ rtcDataChannelEventType),
/* harmony export */   rtcDataChannelGetAvailableAmount: () => (/* binding */ rtcDataChannelGetAvailableAmount),
/* harmony export */   rtcDataChannelGetBufferedAmount: () => (/* binding */ rtcDataChannelGetBufferedAmount),
/* harmony export */   rtcDataChannelGetMaxPacketLifeTime: () => (/* binding */ rtcDataChannelGetMaxPacketLifeTime),
/* harmony export */   rtcDataChannelGetMaxRetransmits: () => (/* binding */ rtcDataChannelGetMaxRetransmits),
/* harmony export */   rtcDataChannelGetProtocol: () => (/* binding */ rtcDataChannelGetProtocol),
/* harmony export */   rtcDataChannelIsOrdered: () => (/* binding */ rtcDataChannelIsOrdered),
/* harmony export */   rtcDataChannelIsReliable: () => (/* binding */ rtcDataChannelIsReliable),
/* harmony export */   rtcDataChannelSetBufferedAmountLowThreshold: () => (/* binding */ rtcDataChannelSetBufferedAmountLowThreshold),
/* harmony export */   rtcGetMaxMessageSize: () => (/* binding */ rtcGetMaxMessageSize),
/* harmony export */   rtcInitDataChannelEventQueue: () => (/* binding */ rtcInitDataChannelEventQueue),
/* harmony export */   rtcInitPeerEventQueue: () => (/* binding */ rtcInitPeerEventQueue),
/* harmony export */   rtcPeerConsumeEvent: () => (/* binding */ rtcPeerConsumeEvent),
/* harmony export */   rtcPeerEventCandidate: () => (/* binding */ rtcPeerEventCandidate),
/* harmony export */   rtcPeerEventChannel: () => (/* binding */ rtcPeerEventChannel),
/* harmony export */   rtcPeerEventState: () => (/* binding */ rtcPeerEventState),
/* harmony export */   rtcPeerEventType: () => (/* binding */ rtcPeerEventType),
/* harmony export */   rtcReadDataChannelBinaryEvent: () => (/* binding */ rtcReadDataChannelBinaryEvent),
/* harmony export */   rtcSetLocalDescriptionAsync: () => (/* binding */ rtcSetLocalDescriptionAsync),
/* harmony export */   rtcSetLocalDescriptionPromise: () => (/* binding */ rtcSetLocalDescriptionPromise),
/* harmony export */   rtcSetOnMessageHandler: () => (/* binding */ rtcSetOnMessageHandler),
/* harmony export */   rtcSetRemoteDescriptionAsync: () => (/* binding */ rtcSetRemoteDescriptionAsync),
/* harmony export */   rtcSetRemoteDescriptionPromise: () => (/* binding */ rtcSetRemoteDescriptionPromise),
/* harmony export */   scryptAsync: () => (/* binding */ scryptAsync),
/* harmony export */   scryptBufferPromise: () => (/* binding */ scryptBufferPromise),
/* harmony export */   secp256k1PrivateKeyVerify: () => (/* binding */ secp256k1PrivateKeyVerify),
/* harmony export */   secp256k1PrivateKeyVerifyBuffer: () => (/* binding */ secp256k1PrivateKeyVerifyBuffer),
/* harmony export */   secp256k1PublicKeyCreate: () => (/* binding */ secp256k1PublicKeyCreate),
/* harmony export */   secp256k1PublicKeyCreateBuffer: () => (/* binding */ secp256k1PublicKeyCreateBuffer),
/* harmony export */   secp256k1PublicKeyVerify: () => (/* binding */ secp256k1PublicKeyVerify),
/* harmony export */   secp256k1PublicKeyVerifyBuffer: () => (/* binding */ secp256k1PublicKeyVerifyBuffer),
/* harmony export */   secp256k1RecoverPublicKey: () => (/* binding */ secp256k1RecoverPublicKey),
/* harmony export */   secp256k1RecoverPublicKeyBuffer: () => (/* binding */ secp256k1RecoverPublicKeyBuffer),
/* harmony export */   secp256k1SharedSecret: () => (/* binding */ secp256k1SharedSecret),
/* harmony export */   secp256k1SharedSecretBuffer: () => (/* binding */ secp256k1SharedSecretBuffer),
/* harmony export */   secp256k1SignRecoverable: () => (/* binding */ secp256k1SignRecoverable),
/* harmony export */   secp256k1SignRecoverableBuffer: () => (/* binding */ secp256k1SignRecoverableBuffer),
/* harmony export */   setClipboardContent: () => (/* binding */ setClipboardContent),
/* harmony export */   setTimeout: () => (/* binding */ TeaVMBinds_setTimeout),
/* harmony export */   sha256: () => (/* binding */ sha256),
/* harmony export */   sha256Buffer: () => (/* binding */ sha256Buffer),
/* harmony export */   sign: () => (/* binding */ sign),
/* harmony export */   signBuffer: () => (/* binding */ signBuffer),
/* harmony export */   toJSON: () => (/* binding */ toJSON),
/* harmony export */   verify: () => (/* binding */ verify),
/* harmony export */   verifyBuffer: () => (/* binding */ verifyBuffer),
/* harmony export */   vfileDeleteAsync: () => (/* binding */ vfileDeleteAsync),
/* harmony export */   vfileDeletePromise: () => (/* binding */ vfileDeletePromise),
/* harmony export */   vfileExistsAsync: () => (/* binding */ vfileExistsAsync),
/* harmony export */   vfileExistsPromise: () => (/* binding */ vfileExistsPromise),
/* harmony export */   vfileListAllAsync: () => (/* binding */ vfileListAllAsync),
/* harmony export */   vfileListAllPromise: () => (/* binding */ vfileListAllPromise),
/* harmony export */   vfileReadAsync: () => (/* binding */ vfileReadAsync),
/* harmony export */   vfileReadPromise: () => (/* binding */ vfileReadPromise),
/* harmony export */   vfileWriteAsync: () => (/* binding */ vfileWriteAsync),
/* harmony export */   vfileWritePromise: () => (/* binding */ vfileWritePromise),
/* harmony export */   websocketConsumeEvent: () => (/* binding */ websocketConsumeEvent),
/* harmony export */   websocketEventBinaryLength: () => (/* binding */ websocketEventBinaryLength),
/* harmony export */   websocketEventText: () => (/* binding */ websocketEventText),
/* harmony export */   websocketEventType: () => (/* binding */ websocketEventType),
/* harmony export */   websocketInitEventQueue: () => (/* binding */ websocketInitEventQueue),
/* harmony export */   websocketOpenPromise: () => (/* binding */ websocketOpenPromise),
/* harmony export */   websocketReadBinaryEvent: () => (/* binding */ websocketReadBinaryEvent),
/* harmony export */   xchacha20poly1305: () => (/* binding */ xchacha20poly1305),
/* harmony export */   xchacha20poly1305Buffer: () => (/* binding */ xchacha20poly1305Buffer)
/* harmony export */ });
/* harmony import */ var _noble_ciphers_chacha_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(943);
/* harmony import */ var _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(329);
/* harmony import */ var _noble_hashes_hmac_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(454);
/* harmony import */ var _noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(429);
/* harmony import */ var _noble_hashes_hkdf__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(962);
/* harmony import */ var _scure_base__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(324);
/* harmony import */ var _noble_ciphers_aes__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(60);
/* harmony import */ var _noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(976);
/* harmony import */ var _noble_hashes_scrypt__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(430);
/* provided dependency */ var Buffer = __webpack_require__(287)["Buffer"];
function _toConsumableArray(r) { return _arrayWithoutHoles(r) || _iterableToArray(r) || _unsupportedIterableToArray(r) || _nonIterableSpread(); }
function _nonIterableSpread() { throw new TypeError("Invalid attempt to spread non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method."); }
function _iterableToArray(r) { if ("undefined" != typeof Symbol && null != r[Symbol.iterator] || null != r["@@iterator"]) return Array.from(r); }
function _arrayWithoutHoles(r) { if (Array.isArray(r)) return _arrayLikeToArray(r); }
function _createForOfIteratorHelper(r, e) { var t = "undefined" != typeof Symbol && r[Symbol.iterator] || r["@@iterator"]; if (!t) { if (Array.isArray(r) || (t = _unsupportedIterableToArray(r)) || e && r && "number" == typeof r.length) { t && (r = t); var _n = 0, F = function F() {}; return { s: F, n: function n() { return _n >= r.length ? { done: !0 } : { done: !1, value: r[_n++] }; }, e: function e(r) { throw r; }, f: F }; } throw new TypeError("Invalid attempt to iterate non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method."); } var o, a = !0, u = !1; return { s: function s() { t = t.call(r); }, n: function n() { var r = t.next(); return a = r.done, r; }, e: function e(r) { u = !0, o = r; }, f: function f() { try { a || null == t["return"] || t["return"](); } finally { if (u) throw o; } } }; }
function _unsupportedIterableToArray(r, a) { if (r) { if ("string" == typeof r) return _arrayLikeToArray(r, a); var t = {}.toString.call(r).slice(8, -1); return "Object" === t && r.constructor && (t = r.constructor.name), "Map" === t || "Set" === t ? Array.from(r) : "Arguments" === t || /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(t) ? _arrayLikeToArray(r, a) : void 0; } }
function _arrayLikeToArray(r, a) { (null == a || a > r.length) && (a = r.length); for (var e = 0, n = Array(a); e < a; e++) n[e] = r[e]; return n; }
function _regenerator() { /*! regenerator-runtime -- Copyright (c) 2014-present, Facebook, Inc. -- license (MIT): https://github.com/babel/babel/blob/main/packages/babel-helpers/LICENSE */ var e, t, r = "function" == typeof Symbol ? Symbol : {}, n = r.iterator || "@@iterator", o = r.toStringTag || "@@toStringTag"; function i(r, n, o, i) { var c = n && n.prototype instanceof Generator ? n : Generator, u = Object.create(c.prototype); return _regeneratorDefine2(u, "_invoke", function (r, n, o) { var i, c, u, f = 0, p = o || [], y = !1, G = { p: 0, n: 0, v: e, a: d, f: d.bind(e, 4), d: function d(t, r) { return i = t, c = 0, u = e, G.n = r, a; } }; function d(r, n) { for (c = r, u = n, t = 0; !y && f && !o && t < p.length; t++) { var o, i = p[t], d = G.p, l = i[2]; r > 3 ? (o = l === n) && (u = i[(c = i[4]) ? 5 : (c = 3, 3)], i[4] = i[5] = e) : i[0] <= d && ((o = r < 2 && d < i[1]) ? (c = 0, G.v = n, G.n = i[1]) : d < l && (o = r < 3 || i[0] > n || n > l) && (i[4] = r, i[5] = n, G.n = l, c = 0)); } if (o || r > 1) return a; throw y = !0, n; } return function (o, p, l) { if (f > 1) throw TypeError("Generator is already running"); for (y && 1 === p && d(p, l), c = p, u = l; (t = c < 2 ? e : u) || !y;) { i || (c ? c < 3 ? (c > 1 && (G.n = -1), d(c, u)) : G.n = u : G.v = u); try { if (f = 2, i) { if (c || (o = "next"), t = i[o]) { if (!(t = t.call(i, u))) throw TypeError("iterator result is not an object"); if (!t.done) return t; u = t.value, c < 2 && (c = 0); } else 1 === c && (t = i["return"]) && t.call(i), c < 2 && (u = TypeError("The iterator does not provide a '" + o + "' method"), c = 1); i = e; } else if ((t = (y = G.n < 0) ? u : r.call(n, G)) !== a) break; } catch (t) { i = e, c = 1, u = t; } finally { f = 1; } } return { value: t, done: y }; }; }(r, o, i), !0), u; } var a = {}; function Generator() {} function GeneratorFunction() {} function GeneratorFunctionPrototype() {} t = Object.getPrototypeOf; var c = [][n] ? t(t([][n]())) : (_regeneratorDefine2(t = {}, n, function () { return this; }), t), u = GeneratorFunctionPrototype.prototype = Generator.prototype = Object.create(c); function f(e) { return Object.setPrototypeOf ? Object.setPrototypeOf(e, GeneratorFunctionPrototype) : (e.__proto__ = GeneratorFunctionPrototype, _regeneratorDefine2(e, o, "GeneratorFunction")), e.prototype = Object.create(u), e; } return GeneratorFunction.prototype = GeneratorFunctionPrototype, _regeneratorDefine2(u, "constructor", GeneratorFunctionPrototype), _regeneratorDefine2(GeneratorFunctionPrototype, "constructor", GeneratorFunction), GeneratorFunction.displayName = "GeneratorFunction", _regeneratorDefine2(GeneratorFunctionPrototype, o, "GeneratorFunction"), _regeneratorDefine2(u), _regeneratorDefine2(u, o, "Generator"), _regeneratorDefine2(u, n, function () { return this; }), _regeneratorDefine2(u, "toString", function () { return "[object Generator]"; }), (_regenerator = function _regenerator() { return { w: i, m: f }; })(); }
function _regeneratorDefine2(e, r, n, t) { var i = Object.defineProperty; try { i({}, "", {}); } catch (e) { i = 0; } _regeneratorDefine2 = function _regeneratorDefine(e, r, n, t) { function o(r, n) { _regeneratorDefine2(e, r, function (e) { return this._invoke(r, n, e); }); } r ? i ? i(e, r, { value: n, enumerable: !t, configurable: !t, writable: !t }) : e[r] = n : (o("next", 0), o("throw", 1), o("return", 2)); }, _regeneratorDefine2(e, r, n, t); }
function asyncGeneratorStep(n, t, e, r, o, a, c) { try { var i = n[a](c), u = i.value; } catch (n) { return void e(n); } i.done ? t(u) : Promise.resolve(u).then(r, o); }
function _asyncToGenerator(n) { return function () { var t = this, e = arguments; return new Promise(function (r, o) { var a = n.apply(t, e); function _next(n) { asyncGeneratorStep(a, r, o, _next, _throw, "next", n); } function _throw(n) { asyncGeneratorStep(a, r, o, _next, _throw, "throw", n); } _next(void 0); }); }; }
function _typeof(o) { "@babel/helpers - typeof"; return _typeof = "function" == typeof Symbol && "symbol" == typeof Symbol.iterator ? function (o) { return typeof o; } : function (o) { return o && "function" == typeof Symbol && o.constructor === Symbol && o !== Symbol.prototype ? "symbol" : typeof o; }, _typeof(o); }











// convert various buffer types to Uint8Array
var _u = function _u(data) {
  if (data instanceof Uint8Array) {
    return data;
  } else if (data instanceof Int8Array) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  } else if (Array.isArray(data)) {
    return new Uint8Array(data);
  } else if (data instanceof ArrayBuffer) {
    return new Uint8Array(data);
  } else if (data instanceof Uint8ClampedArray) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  } else if (data instanceof DataView) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  } else if (data instanceof Buffer) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  } else {
    console.trace();
    throw new TypeError('Unsupported data type for conversion to Uint8Array ' + _typeof(data));
  }
};
var _writeBytes = function _writeBytes(output, value) {
  var target = _u(output);
  var source = _u(value);
  if (target.length < source.length) {
    throw new RangeError("Output buffer is too small: required ".concat(source.length, ", available ").concat(target.length));
  }
  target.set(source);
  return source.length;
};

// wrap Uint8Array in an object 
var _bw = function _bw(data) {
  return {
    data: data
  };
};
function _s() {
  return typeof window !== 'undefined' && window || typeof globalThis !== 'undefined' && globalThis || typeof __webpack_require__.g !== 'undefined' && __webpack_require__.g || typeof self !== 'undefined' && self;
}
function _isNodeRuntime() {
  return typeof process !== 'undefined' && process !== null && typeof process.versions !== 'undefined' && process.versions !== null && typeof process.versions.node === 'string';
}
function _getNodeVersion() {
  return _isNodeRuntime() ? process.versions.node : null;
}
function _hasIndexedDB() {
  var _s2;
  return !!((_s2 = _s()) !== null && _s2 !== void 0 && _s2.indexedDB);
}
var _dynamicImport = function _dynamicImport(specifier) {
  try {
    return Function('s', 'return import(s)')(specifier);
  } catch (error) {
    return Promise.reject(error);
  }
};
function _getNodeVStoreSafeName(name) {
  if (name === null || name === undefined) {
    throw new Error('VStore name is required.');
  }
  var s = String(name);
  if (!s) {
    throw new Error('VStore name is required.');
  }
  return encodeURIComponent(s);
}
function _pathStartsWith(pathModule, candidatePath, basePath) {
  var candidate = pathModule.resolve(candidatePath);
  var base = pathModule.resolve(basePath);
  if (candidate === base) {
    return true;
  }
  if (process.platform === 'win32') {
    var c = candidate.toLowerCase();
    var b = base.toLowerCase();
    return c.startsWith(b + pathModule.sep);
  }
  return candidate.startsWith(base + pathModule.sep);
}
function _resolveNodeVStorePath(pathModule, basePath, userPath) {
  if (userPath === null || userPath === undefined || String(userPath).length === 0) {
    throw new Error('Path required');
  }
  var p = String(userPath);
  if (pathModule.isAbsolute(p)) {
    throw new Error('Absolute paths not allowed');
  }
  var candidate = pathModule.resolve(basePath, p);
  if (!_pathStartsWith(pathModule, candidate, basePath)) {
    throw new Error('Traversal detected');
  }
  return candidate;
}
var _getNodeDataPath = /*#__PURE__*/function () {
  var _ref = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee() {
    var s, osModule, pathModule, env, basePath;
    return _regenerator().w(function (_context) {
      while (1) switch (_context.n) {
        case 0:
          if (_isNodeRuntime()) {
            _context.n = 1;
            break;
          }
          return _context.a(2, null);
        case 1:
          s = _s();
          if (!s._ngeNodeDataPath) {
            _context.n = 2;
            break;
          }
          return _context.a(2, s._ngeNodeDataPath);
        case 2:
          _context.n = 3;
          return _dynamicImport('node:os');
        case 3:
          osModule = _context.v;
          _context.n = 4;
          return _dynamicImport('node:path');
        case 4:
          pathModule = _context.v;
          env = process.env || {};
          basePath = env.APP_DATA_DIR;
          if (!basePath) {
            if (process.platform === 'win32') {
              basePath = env.APPDATA || pathModule.join(osModule.homedir(), 'AppData', 'Roaming');
            } else if (process.platform === 'darwin') {
              basePath = pathModule.join(osModule.homedir(), 'Library', 'Application Support');
            } else {
              basePath = env.XDG_DATA_HOME || pathModule.join(osModule.homedir(), '.local', 'share');
            }
          }
          s._ngeNodeDataPath = pathModule.resolve(basePath);
          return _context.a(2, s._ngeNodeDataPath);
      }
    }, _callee);
  }));
  return function _getNodeDataPath() {
    return _ref.apply(this, arguments);
  };
}();
var _getNodeFSVFileStore = /*#__PURE__*/function () {
  var _ref2 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee8(name) {
    var s, fsModule, pathModule, baseDataPath, safeName, basePath, store;
    return _regenerator().w(function (_context8) {
      while (1) switch (_context8.n) {
        case 0:
          if (_isNodeRuntime()) {
            _context8.n = 1;
            break;
          }
          return _context8.a(2, null);
        case 1:
          s = _s();
          if (typeof s._ngeNodeFSVFileStores === 'undefined') {
            s._ngeNodeFSVFileStores = {};
          }
          if (!s._ngeNodeFSVFileStores[name]) {
            _context8.n = 2;
            break;
          }
          return _context8.a(2, s._ngeNodeFSVFileStores[name]);
        case 2:
          _context8.n = 3;
          return _dynamicImport('node:fs/promises');
        case 3:
          fsModule = _context8.v;
          _context8.n = 4;
          return _dynamicImport('node:path');
        case 4:
          pathModule = _context8.v;
          _context8.n = 5;
          return _getNodeDataPath();
        case 5:
          baseDataPath = _context8.v;
          safeName = _getNodeVStoreSafeName(name);
          basePath = pathModule.resolve(baseDataPath, safeName);
          store = {
            close: function close() {},
            exists: function exists(path) {
              return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee2() {
                var fullPath, _t;
                return _regenerator().w(function (_context2) {
                  while (1) switch (_context2.p = _context2.n) {
                    case 0:
                      _context2.p = 0;
                      fullPath = _resolveNodeVStorePath(pathModule, basePath, path);
                      _context2.n = 1;
                      return fsModule.access(fullPath);
                    case 1:
                      return _context2.a(2, true);
                    case 2:
                      _context2.p = 2;
                      _t = _context2.v;
                      return _context2.a(2, false);
                  }
                }, _callee2, null, [[0, 2]]);
              }))();
            },
            read: function read(path) {
              return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee3() {
                var fullPath, bytes, _t2;
                return _regenerator().w(function (_context3) {
                  while (1) switch (_context3.p = _context3.n) {
                    case 0:
                      _context3.p = 0;
                      fullPath = _resolveNodeVStorePath(pathModule, basePath, path);
                      _context3.n = 1;
                      return fsModule.readFile(fullPath);
                    case 1:
                      bytes = _context3.v;
                      return _context3.a(2, new Uint8Array(bytes));
                    case 2:
                      _context3.p = 2;
                      _t2 = _context3.v;
                      return _context3.a(2, null);
                  }
                }, _callee3, null, [[0, 2]]);
              }))();
            },
            write: function write(path, data) {
              return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee4() {
                var fullPath, parent, tmpPath, payload, _t3, _t4;
                return _regenerator().w(function (_context4) {
                  while (1) switch (_context4.p = _context4.n) {
                    case 0:
                      fullPath = _resolveNodeVStorePath(pathModule, basePath, path);
                      parent = pathModule.dirname(fullPath);
                      _context4.n = 1;
                      return fsModule.mkdir(parent, {
                        recursive: true
                      });
                    case 1:
                      tmpPath = pathModule.join(parent, ".vstore-".concat(Date.now(), "-").concat(Math.random().toString(16).slice(2), ".tmp"));
                      payload = _u(data);
                      _context4.n = 2;
                      return fsModule.writeFile(tmpPath, payload);
                    case 2:
                      _context4.p = 2;
                      _context4.n = 3;
                      return fsModule.rename(tmpPath, fullPath);
                    case 3:
                      _context4.n = 9;
                      break;
                    case 4:
                      _context4.p = 4;
                      _t3 = _context4.v;
                      _context4.p = 5;
                      _context4.n = 6;
                      return fsModule.unlink(tmpPath);
                    case 6:
                      _context4.n = 8;
                      break;
                    case 7:
                      _context4.p = 7;
                      _t4 = _context4.v;
                    case 8:
                      throw _t3;
                    case 9:
                      return _context4.a(2);
                  }
                }, _callee4, null, [[5, 7], [2, 4]]);
              }))();
            },
            "delete": function _delete(path) {
              return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee5() {
                var fullPath, _t5;
                return _regenerator().w(function (_context5) {
                  while (1) switch (_context5.p = _context5.n) {
                    case 0:
                      _context5.p = 0;
                      fullPath = _resolveNodeVStorePath(pathModule, basePath, path);
                      _context5.n = 1;
                      return fsModule.unlink(fullPath);
                    case 1:
                      _context5.n = 4;
                      break;
                    case 2:
                      _context5.p = 2;
                      _t5 = _context5.v;
                      if (!(_t5 && _t5.code === 'ENOENT')) {
                        _context5.n = 3;
                        break;
                      }
                      return _context5.a(2);
                    case 3:
                      throw _t5;
                    case 4:
                      return _context5.a(2);
                  }
                }, _callee5, null, [[0, 2]]);
              }))();
            },
            listAll: function listAll() {
              return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee7() {
                var out, _walk, _t7, _t8;
                return _regenerator().w(function (_context7) {
                  while (1) switch (_context7.p = _context7.n) {
                    case 0:
                      out = [];
                      _context7.p = 1;
                      _context7.n = 2;
                      return fsModule.mkdir(basePath, {
                        recursive: true
                      });
                    case 2:
                      _context7.n = 4;
                      break;
                    case 3:
                      _context7.p = 3;
                      _t7 = _context7.v;
                    case 4:
                      _walk = /*#__PURE__*/function () {
                        var _ref3 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee6(dir) {
                          var entries, _iterator, _step, entry, full, rel, _t6;
                          return _regenerator().w(function (_context6) {
                            while (1) switch (_context6.p = _context6.n) {
                              case 0:
                                _context6.n = 1;
                                return fsModule.readdir(dir, {
                                  withFileTypes: true
                                });
                              case 1:
                                entries = _context6.v;
                                _iterator = _createForOfIteratorHelper(entries);
                                _context6.p = 2;
                                _iterator.s();
                              case 3:
                                if ((_step = _iterator.n()).done) {
                                  _context6.n = 7;
                                  break;
                                }
                                entry = _step.value;
                                full = pathModule.join(dir, entry.name);
                                if (!entry.isDirectory()) {
                                  _context6.n = 5;
                                  break;
                                }
                                _context6.n = 4;
                                return _walk(full);
                              case 4:
                                _context6.n = 6;
                                break;
                              case 5:
                                if (entry.isFile()) {
                                  rel = pathModule.relative(basePath, full);
                                  out.push(rel);
                                }
                              case 6:
                                _context6.n = 3;
                                break;
                              case 7:
                                _context6.n = 9;
                                break;
                              case 8:
                                _context6.p = 8;
                                _t6 = _context6.v;
                                _iterator.e(_t6);
                              case 9:
                                _context6.p = 9;
                                _iterator.f();
                                return _context6.f(9);
                              case 10:
                                return _context6.a(2);
                            }
                          }, _callee6, null, [[2, 8, 9, 10]]);
                        }));
                        return function walk(_x2) {
                          return _ref3.apply(this, arguments);
                        };
                      }();
                      _context7.p = 5;
                      _context7.n = 6;
                      return _walk(basePath);
                    case 6:
                      _context7.n = 9;
                      break;
                    case 7:
                      _context7.p = 7;
                      _t8 = _context7.v;
                      if (!(_t8 && _t8.code === 'ENOENT')) {
                        _context7.n = 8;
                        break;
                      }
                      return _context7.a(2, []);
                    case 8:
                      throw _t8;
                    case 9:
                      return _context7.a(2, out);
                  }
                }, _callee7, null, [[5, 7], [1, 3]]);
              }))();
            }
          };
          s._ngeNodeFSVFileStores[name] = store;
          return _context8.a(2, store);
      }
    }, _callee8);
  }));
  return function _getNodeFSVFileStore(_x) {
    return _ref2.apply(this, arguments);
  };
}();
function _getMemoryVFileStore(name) {
  var s = _s();
  if (typeof s._ngeMemoryVFileStores === 'undefined') {
    s._ngeMemoryVFileStores = {};
  }
  if (!s._ngeMemoryVFileStores[name]) {
    s._ngeMemoryVFileStores[name] = new Map();
  }
  var store = s._ngeMemoryVFileStores[name];
  return {
    close: function close() {},
    exists: function exists(path) {
      return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee9() {
        return _regenerator().w(function (_context9) {
          while (1) switch (_context9.n) {
            case 0:
              return _context9.a(2, store.has(path));
          }
        }, _callee9);
      }))();
    },
    read: function read(path) {
      return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee0() {
        var data;
        return _regenerator().w(function (_context0) {
          while (1) switch (_context0.n) {
            case 0:
              if (store.has(path)) {
                _context0.n = 1;
                break;
              }
              return _context0.a(2, null);
            case 1:
              data = store.get(path);
              return _context0.a(2, data ? new Uint8Array(data) : null);
          }
        }, _callee0);
      }))();
    },
    write: function write(path, data) {
      return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee1() {
        return _regenerator().w(function (_context1) {
          while (1) switch (_context1.n) {
            case 0:
              store.set(path, new Uint8Array(_u(data)));
            case 1:
              return _context1.a(2);
          }
        }, _callee1);
      }))();
    },
    "delete": function _delete(path) {
      return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee10() {
        return _regenerator().w(function (_context10) {
          while (1) switch (_context10.n) {
            case 0:
              store["delete"](path);
            case 1:
              return _context10.a(2);
          }
        }, _callee10);
      }))();
    },
    listAll: function listAll() {
      return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee11() {
        return _regenerator().w(function (_context11) {
          while (1) switch (_context11.n) {
            case 0:
              return _context11.a(2, Array.from(store.keys()));
          }
        }, _callee11);
      }))();
    }
  };
}
function _getRTCPeerConnectionCtor() {
  var _s3;
  var ctor = (_s3 = _s()) === null || _s3 === void 0 ? void 0 : _s3.RTCPeerConnection;
  if (typeof ctor === 'function') {
    return ctor;
  }
  if (_isNodeRuntime()) {
    throw new Error('RTCPeerConnection is not available in Node.js. Provide a node-webrtc-compatible implementation in your app and assign it to globalThis.RTCPeerConnection before using TeaVM RTC.');
  }
  throw new Error('RTCPeerConnection is not available in this runtime.');
}
function _getRTCIceCandidateCtor() {
  var _s4;
  var ctor = (_s4 = _s()) === null || _s4 === void 0 ? void 0 : _s4.RTCIceCandidate;
  if (typeof ctor === 'function') {
    return ctor;
  }
  if (_isNodeRuntime()) {
    throw new Error('RTCIceCandidate is not available in Node.js. Provide a node-webrtc-compatible implementation in your app and assign it to globalThis.RTCIceCandidate before using TeaVM RTC.');
  }
  throw new Error('RTCIceCandidate is not available in this runtime.');
}
var _getNodeClipboard = /*#__PURE__*/function () {
  var _ref4 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee14() {
    var s;
    return _regenerator().w(function (_context14) {
      while (1) switch (_context14.n) {
        case 0:
          if (_isNodeRuntime()) {
            _context14.n = 1;
            break;
          }
          return _context14.a(2, null);
        case 1:
          s = _s();
          if (!s._ngeNodeClipboard) {
            _context14.n = 2;
            break;
          }
          return _context14.a(2, s._ngeNodeClipboard);
        case 2:
          s._ngeNodeClipboard = {
            readText: function readText() {
              return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee12() {
                return _regenerator().w(function (_context12) {
                  while (1) switch (_context12.n) {
                    case 0:
                      return _context12.a(2, '');
                  }
                }, _callee12);
              }))();
            },
            writeText: function writeText(text) {
              return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee13() {
                return _regenerator().w(function (_context13) {
                  while (1) switch (_context13.n) {
                    case 0:
                      return _context13.a(2);
                  }
                }, _callee13);
              }))();
            }
          };
          return _context14.a(2, s._ngeNodeClipboard);
      }
    }, _callee14);
  }));
  return function _getNodeClipboard() {
    return _ref4.apply(this, arguments);
  };
}();
var _getClipboard = /*#__PURE__*/function () {
  var _ref5 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee15() {
    var _s5;
    var injectedClipboard;
    return _regenerator().w(function (_context15) {
      while (1) switch (_context15.n) {
        case 0:
          injectedClipboard = (_s5 = _s()) === null || _s5 === void 0 ? void 0 : _s5.ngeClipboard;
          if (!injectedClipboard) {
            _context15.n = 1;
            break;
          }
          return _context15.a(2, injectedClipboard);
        case 1:
          if (!(typeof navigator !== 'undefined' && navigator && navigator.clipboard)) {
            _context15.n = 2;
            break;
          }
          return _context15.a(2, navigator.clipboard);
        case 2:
          if (!_isNodeRuntime()) {
            _context15.n = 3;
            break;
          }
          return _context15.a(2, _getNodeClipboard());
        case 3:
          return _context15.a(2, null);
      }
    }, _callee15);
  }));
  return function _getClipboard() {
    return _ref5.apply(this, arguments);
  };
}();
var _sanitizeBigInts = function sanitizeBigInts(obj) {
  // Base cases for non-objects
  if (obj === null || obj === undefined) {
    return obj;
  }

  // Convert BigInt to Number
  if (typeof obj === 'bigint') {
    return Number(obj);
  }

  // Handle arrays
  if (Array.isArray(obj)) {
    return obj.map(function (item) {
      return _sanitizeBigInts(item);
    });
  }

  // Handle {}
  if (_typeof(obj) === 'object' && Object.getPrototypeOf(obj) === Object.prototype) {
    var result = {};
    for (var key in obj) {
      if (Object.hasOwnProperty.call(obj, key)) {
        result[key] = _sanitizeBigInts(obj[key]);
      }
    }
    return result;
  }

  // Return all other types unchanged
  return obj;
};
var randomBytes = function randomBytes(length /*int*/) {
  // Uint8Array (byte[])
  return _u((0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_7__.randomBytes)(length));
};
var randomBytesBuffer = function randomBytesBuffer(output) {
  return _writeBytes(output, (0,_noble_hashes_utils_js__WEBPACK_IMPORTED_MODULE_7__.randomBytes)(_u(output).length));
};
var generatePrivateKey = function generatePrivateKey() {
  // Uint8Array (byte[])
  return _u(_noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.schnorr.utils.randomPrivateKey());
};
var generatePrivateKeyBuffer = function generatePrivateKeyBuffer(output) {
  return _writeBytes(output, _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.schnorr.utils.randomPrivateKey());
};
var genPubKey = function genPubKey(secKey) {
  // Uint8Array (byte[])
  return _u(_noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.schnorr.getPublicKey(_u(secKey)));
};
var genPubKeyBuffer = function genPubKeyBuffer(secKey, output) {
  return _writeBytes(output, _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.schnorr.getPublicKey(_u(secKey)));
};
var sha256 = function sha256(data /*byte[]*/) {
  // Uint8Array (byte[])
  return _u((0,_noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_3__.sha256)(_u(data)));
};
var sha256Buffer = function sha256Buffer(data, output) {
  return _writeBytes(output, (0,_noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_3__.sha256)(_u(data)));
};
var toJSON = function toJSON(obj /*obj*/) {
  // str
  return JSON.stringify(_sanitizeBigInts(obj), null, 0);
};
var fromJSON = function fromJSON(json /*str*/) {
  try {
    return JSON.parse(json); // obj
  } catch (e) {
    console.error('Error parsing JSON:', json, e);
    throw e;
  }
};
var sign = function sign(data /*byte[]*/, privKeyBytes /*byte[]*/) {
  // Uint8Array (byte[])
  return _u(_noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.schnorr.sign(_u(data), _u(privKeyBytes)));
};
var signBuffer = function signBuffer(data, privKeyBytes, output) {
  return _writeBytes(output, _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.schnorr.sign(_u(data), _u(privKeyBytes)));
};
var verify = function verify(data /*byte[]*/, pub /*byte[]*/, sig /*byte[]*/) {
  // bool
  return _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.schnorr.verify(_u(sig), _u(data), _u(pub));
};
var verifyBuffer = function verifyBuffer(data, pub, sig) {
  return _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.schnorr.verify(_u(sig), _u(data), _u(pub));
};
var secp256k1SharedSecret = function secp256k1SharedSecret(privKey /*byte[]*/, pubKey /*byte[]*/) {
  // Uint8Array (byte[])
  if (!secp256k1PrivateKeyVerify(privKey)) throw new TypeError('Invalid secp256k1 private key');
  if (!secp256k1PublicKeyVerify(pubKey)) throw new TypeError('Invalid secp256k1 public key');
  var sharedPoint = _u(_noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.secp256k1.getSharedSecret(_u(privKey), _u(pubKey)));
  if (!secp256k1PublicKeyVerify(sharedPoint)) throw new TypeError('Invalid secp256k1 shared point');
  return sharedPoint;
};
var secp256k1SharedSecretBuffer = function secp256k1SharedSecretBuffer(privKey, pubKey, output) {
  return _writeBytes(output, secp256k1SharedSecret(privKey, pubKey));
};
var secp256k1PrivateKeyVerify = function secp256k1PrivateKeyVerify(privateKey /*byte[]*/) {
  // bool
  try {
    return _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.secp256k1.utils.isValidSecretKey(_u(privateKey));
  } catch (_error) {
    return false;
  }
};
var secp256k1PrivateKeyVerifyBuffer = function secp256k1PrivateKeyVerifyBuffer(privateKey) {
  return secp256k1PrivateKeyVerify(privateKey);
};
var secp256k1PublicKeyVerify = function secp256k1PublicKeyVerify(publicKey /*byte[]*/) {
  // bool
  try {
    return _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.secp256k1.utils.isValidPublicKey(_u(publicKey));
  } catch (_error) {
    return false;
  }
};
var secp256k1PublicKeyVerifyBuffer = function secp256k1PublicKeyVerifyBuffer(publicKey) {
  return secp256k1PublicKeyVerify(publicKey);
};
var secp256k1PublicKeyCreate = function secp256k1PublicKeyCreate(privateKey /*byte[]*/, compressed /*bool*/) {
  // Uint8Array (byte[])
  return _u(_noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.secp256k1.getPublicKey(_u(privateKey), !!compressed));
};
var secp256k1PublicKeyCreateBuffer = function secp256k1PublicKeyCreateBuffer(privateKey, compressed, output) {
  return _writeBytes(output, _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.secp256k1.getPublicKey(_u(privateKey), !!compressed));
};
var secp256k1SignRecoverable = function secp256k1SignRecoverable(hash32 /*byte[]*/, privateKey /*byte[]*/) {
  // Uint8Array (byte[])
  var signature = _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.secp256k1.sign(_u(hash32), _u(privateKey), {
    prehash: false,
    lowS: true
  });
  var recoveredSig = signature.toBytes('recovered');
  return _u(recoveredSig);
};
var secp256k1SignRecoverableBuffer = function secp256k1SignRecoverableBuffer(hash32, privateKey, output) {
  return _writeBytes(output, secp256k1SignRecoverable(hash32, privateKey));
};
var secp256k1RecoverPublicKey = function secp256k1RecoverPublicKey(hash32 /*byte[]*/, signature64 /*byte[]*/, recoveryId /*int*/, compressed /*bool*/) {
  // Uint8Array (byte[])
  var sig64 = _u(signature64);
  if (sig64.length !== 64) {
    throw new Error('signature64 must be 64 bytes');
  }
  if (recoveryId < 0 || recoveryId > 3) {
    throw new Error('recoveryId must be in [0..3]');
  }
  var recoveredSig = new Uint8Array(65);
  recoveredSig[0] = recoveryId;
  recoveredSig.set(sig64, 1);
  var signature = _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_1__.secp256k1.Signature.fromBytes(recoveredSig, 'recovered');
  var point = signature.recoverPublicKey(_u(hash32));
  return _u(point.toBytes(!!compressed));
};
var secp256k1RecoverPublicKeyBuffer = function secp256k1RecoverPublicKeyBuffer(hash32, signature64, recoveryId, compressed, output) {
  return _writeBytes(output, secp256k1RecoverPublicKey(hash32, signature64, recoveryId, compressed));
};
var hmac = function hmac(key /*byte[]*/, data1 /*byte[]*/, data2 /*byte[]*/) {
  // Uint8Array (byte[])
  var msg = new Uint8Array([].concat(_toConsumableArray(_u(data1)), _toConsumableArray(_u(data2))));
  return _u((0,_noble_hashes_hmac_js__WEBPACK_IMPORTED_MODULE_2__.hmac)(_noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_3__.sha256, _u(key), msg));
};
var hmacBuffer = function hmacBuffer(key, data1, data2, output) {
  return _writeBytes(output, hmac(key, data1, data2));
};
var hkdf_extract = function hkdf_extract(salt /*byte[]*/, ikm /*byte[]*/) {
  // Uint8Array (byte[])
  return _u((0,_noble_hashes_hkdf__WEBPACK_IMPORTED_MODULE_4__.extract)(_noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_3__.sha256, _u(ikm), _u(salt)));
};
var hkdfExtractBuffer = function hkdfExtractBuffer(salt, ikm, output) {
  return _writeBytes(output, (0,_noble_hashes_hkdf__WEBPACK_IMPORTED_MODULE_4__.extract)(_noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_3__.sha256, _u(ikm), _u(salt)));
};
var hkdf_expand = function hkdf_expand(prk /*byte[]*/, info /*byte[]*/, length /*int*/) {
  // Uint8Array (byte[])
  return _u((0,_noble_hashes_hkdf__WEBPACK_IMPORTED_MODULE_4__.expand)(_noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_3__.sha256, _u(prk), _u(info), length));
};
var hkdfExpandBuffer = function hkdfExpandBuffer(prk, info, length, output) {
  return _writeBytes(output, (0,_noble_hashes_hkdf__WEBPACK_IMPORTED_MODULE_4__.expand)(_noble_hashes_sha2_js__WEBPACK_IMPORTED_MODULE_3__.sha256, _u(prk), _u(info), length));
};
var base64encode = function base64encode(data /*byte[]*/) {
  //str
  return _scure_base__WEBPACK_IMPORTED_MODULE_5__.base64.encode(_u(data));
};
var base64encodeBuffer = function base64encodeBuffer(data) {
  return _scure_base__WEBPACK_IMPORTED_MODULE_5__.base64.encode(_u(data));
};
var base64decode = function base64decode(data /*str*/) {
  // Uint8Array (byte[])
  return _u(_scure_base__WEBPACK_IMPORTED_MODULE_5__.base64.decode(data));
};
var base64decodeBuffer = function base64decodeBuffer(data, output) {
  return _writeBytes(output, _scure_base__WEBPACK_IMPORTED_MODULE_5__.base64.decode(data));
};
var chacha20 = function chacha20(key /*byte[]*/, nonce /*byte[]*/, data /*byte[]*/) {
  // Uint8Array (byte[])
  return _u((0,_noble_ciphers_chacha_js__WEBPACK_IMPORTED_MODULE_0__.chacha20)(_u(key), _u(nonce), _u(data)));
};
var chacha20Buffer = function chacha20Buffer(key, nonce, data, output) {
  return _writeBytes(output, (0,_noble_ciphers_chacha_js__WEBPACK_IMPORTED_MODULE_0__.chacha20)(_u(key), _u(nonce), _u(data)));
};
var TeaVMBinds_setTimeout = function setTimeout(callback, delay) {
  //void
  return _s().setTimeout(callback, delay);
};
var delayPromise = function delayPromise(delay) {
  return new Promise(function (resolve) {
    return _s().setTimeout(resolve, delay);
  });
};
var websocketOpenPromise = function websocketOpenPromise(socket, timeoutMs) {
  return new Promise(function (resolve, reject) {
    var settled = false;
    var timeoutId = _s().setTimeout(function () {
      if (!settled) {
        settled = true;
        reject(new Error('WebSocket connection timeout'));
      }
    }, timeoutMs);
    socket.addEventListener('open', function () {
      if (!settled) {
        settled = true;
        clearTimeout(timeoutId);
        resolve();
      }
    }, {
      once: true
    });
    socket.addEventListener('error', function () {
      if (!settled) {
        settled = true;
        clearTimeout(timeoutId);
        reject(new Error('WebSocket connection failed'));
      }
    }, {
      once: true
    });
  });
};
var _initEventQueue = function _initEventQueue(target) {
  var current = target._ngeEventQueueState;
  if (current && !current.disposed) {
    return current;
  }
  var state = {
    queue: [],
    waitPromise: null,
    wake: null,
    disposed: false
  };
  target._ngeEventQueueState = state;
  return state;
};
var _enqueueEvent = function _enqueueEvent(target, event) {
  var state = target._ngeEventQueueState;
  if (!state || state.disposed) {
    return;
  }
  state.queue.push(event);
  if (state.wake) {
    var wake = state.wake;
    state.wake = null;
    state.waitPromise = null;
    wake();
  }
};
var _currentEvent = function _currentEvent(target) {
  var _target$_ngeEventQueu;
  return (_target$_ngeEventQueu = target._ngeEventQueueState) === null || _target$_ngeEventQueu === void 0 || (_target$_ngeEventQueu = _target$_ngeEventQueu.queue) === null || _target$_ngeEventQueu === void 0 ? void 0 : _target$_ngeEventQueu[0];
};
var eventQueueWaitPromise = function eventQueueWaitPromise(target) {
  var current = target === null || target === void 0 ? void 0 : target._ngeEventQueueState;
  if (current !== null && current !== void 0 && current.disposed) {
    return Promise.resolve();
  }
  var state = current !== null && current !== void 0 ? current : _initEventQueue(target);
  if (state.queue.length > 0) {
    return Promise.resolve();
  }
  if (!state.waitPromise) {
    state.waitPromise = new Promise(function (resolve) {
      state.wake = resolve;
    });
  }
  return state.waitPromise;
};
var eventQueueDispose = function eventQueueDispose(target) {
  var state = target === null || target === void 0 ? void 0 : target._ngeEventQueueState;
  if (!state || state.disposed) {
    return;
  }
  state.disposed = true;
  state.queue.length = 0;
  if (state.wake) {
    var wake = state.wake;
    state.wake = null;
    state.waitPromise = null;
    wake();
  }
};
var websocketInitEventQueue = function websocketInitEventQueue(socket) {
  if (socket._ngeEventQueueState && !socket._ngeEventQueueState.disposed) {
    return;
  }
  _initEventQueue(socket);
  socket.addEventListener('message', function (event) {
    if (typeof event.data === 'string') {
      _enqueueEvent(socket, {
        type: 1,
        text: event.data
      });
    } else {
      _enqueueEvent(socket, {
        type: 2,
        binary: _u(event.data)
      });
    }
  });
  socket.addEventListener('close', function (event) {
    var _event$reason;
    _enqueueEvent(socket, {
      type: 3,
      text: (_event$reason = event.reason) !== null && _event$reason !== void 0 ? _event$reason : ''
    });
  });
  socket.addEventListener('error', function () {
    _enqueueEvent(socket, {
      type: 4,
      text: 'WebSocket error'
    });
  });
};
var websocketEventType = function websocketEventType(socket) {
  var _currentEvent$type, _currentEvent2;
  return (_currentEvent$type = (_currentEvent2 = _currentEvent(socket)) === null || _currentEvent2 === void 0 ? void 0 : _currentEvent2.type) !== null && _currentEvent$type !== void 0 ? _currentEvent$type : 0;
};
var websocketEventText = function websocketEventText(socket) {
  var _currentEvent$text, _currentEvent3;
  return (_currentEvent$text = (_currentEvent3 = _currentEvent(socket)) === null || _currentEvent3 === void 0 ? void 0 : _currentEvent3.text) !== null && _currentEvent$text !== void 0 ? _currentEvent$text : null;
};
var websocketEventBinaryLength = function websocketEventBinaryLength(socket) {
  var _currentEvent$binary$, _currentEvent4;
  return (_currentEvent$binary$ = (_currentEvent4 = _currentEvent(socket)) === null || _currentEvent4 === void 0 || (_currentEvent4 = _currentEvent4.binary) === null || _currentEvent4 === void 0 ? void 0 : _currentEvent4.byteLength) !== null && _currentEvent$binary$ !== void 0 ? _currentEvent$binary$ : 0;
};
var websocketReadBinaryEvent = function websocketReadBinaryEvent(socket, output) {
  var event = _currentEvent(socket);
  if (!event || event.type !== 2) {
    throw new Error('Current WebSocket event is not binary');
  }
  return _writeBytes(output, event.binary);
};
var websocketConsumeEvent = function websocketConsumeEvent(socket) {
  var _socket$_ngeEventQueu;
  (_socket$_ngeEventQueu = socket._ngeEventQueueState) === null || _socket$_ngeEventQueu === void 0 || (_socket$_ngeEventQueu = _socket$_ngeEventQueu.queue) === null || _socket$_ngeEventQueu === void 0 || _socket$_ngeEventQueu.shift();
};
var getClipboardContentAsync = function getClipboardContentAsync(res, rej) {
  //str
  _getClipboard().then(function (clipboard) {
    if (!clipboard || typeof clipboard.readText !== 'function') {
      return '';
    }
    return clipboard.readText();
  }).then(function (text) {
    res(text !== null && text !== void 0 ? text : '');
  })["catch"](function (err) {
    console.error('Failed to read clipboard contents: ', err);
    res('');
  });
};
var getClipboardContentPromise = function getClipboardContentPromise() {
  return _getClipboard().then(function (clipboard) {
    if (!clipboard || typeof clipboard.readText !== 'function') {
      return '';
    }
    return clipboard.readText();
  }).then(function (text) {
    return text !== null && text !== void 0 ? text : '';
  });
};
var setClipboardContent = function setClipboardContent(text) {
  //void
  _getClipboard().then(function (clipboard) {
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      return;
    }
    return clipboard.writeText(text);
  })["catch"](function (err) {
    console.error('Failed to write to clipboard: ', err);
  });
};
var hasBundledResource = function hasBundledResource(path) {
  var _s6;
  // boolean
  if (path.startsWith('/')) {
    path = path.substring(1);
  }
  var bundle = (_s6 = _s()) === null || _s6 === void 0 ? void 0 : _s6.NGEBundledResources;
  if (!bundle) {
    console.warn('No bundled resources found. Ensure the bundler is configured correctly.');
    return false;
  }
  if (!bundle[path]) {
    console.warn('Resource not found in bundle:', path);
    return false;
  }
  return true;
};
var getBundledResource = function getBundledResource(path) {
  var _s7;
  // byte[]

  if (path.startsWith('/')) {
    path = path.substring(1);
  }
  var bundle = (_s7 = _s()) === null || _s7 === void 0 ? void 0 : _s7.NGEBundledResources;
  if (!bundle) {
    console.warn('No bundled resources found. Ensure the bundler is configured correctly.');
    return null;
  }
  if (!bundle[path]) {
    console.warn('Resource not found in bundle:', path);
    return null;
  }
  return _u(base64decode(bundle[path]));
};
var aes256cbc = function aes256cbc(key /*byte[]*/, iv /*byte[]*/, data /*byte[]*/, forEncryption /*bool*/) {
  // Uint8Array (byte[])
  key = _u(key);
  iv = _u(iv);
  data = _u(data);
  if (key.length !== 32) {
    throw new Error('AES-256 requires a 32-byte key');
  }
  if (iv.length !== 16) {
    throw new Error('AES-CBC requires a 16-byte IV');
  }
  try {
    var cipher = (0,_noble_ciphers_aes__WEBPACK_IMPORTED_MODULE_6__.cbc)(key, iv);
    return _u(forEncryption ? cipher.encrypt(data) : cipher.decrypt(data));
  } catch (error) {
    console.error('AES-256-CBC operation failed:', error);
    throw error;
  }
};
var aes256cbcBuffer = function aes256cbcBuffer(key, iv, data, forEncryption, output) {
  return _writeBytes(output, aes256cbc(key, iv, data, forEncryption));
};
function getVFileStore(_x3) {
  return _getVFileStore.apply(this, arguments);
} // OutputStream.close() cannot return a JavaScript Promise. A stream remains
// completely local while it is open; once close starts its IndexedDB commit,
// keep that promise here so subsequent backend operations observe close order.
function _getVFileStore() {
  _getVFileStore = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee33(name) {
    var globalObj, injectedStore, _t14;
    return _regenerator().w(function (_context33) {
      while (1) switch (_context33.p = _context33.n) {
        case 0:
          globalObj = _s();
          if (!(typeof (globalObj === null || globalObj === void 0 ? void 0 : globalObj.ngeVStoreFactory) === 'function')) {
            _context33.n = 3;
            break;
          }
          _context33.n = 1;
          return globalObj.ngeVStoreFactory(name);
        case 1:
          injectedStore = _context33.v;
          if (injectedStore) {
            _context33.n = 2;
            break;
          }
          throw new Error("Injected VStore factory returned no store for ".concat(name));
        case 2:
          return _context33.a(2, injectedStore);
        case 3:
          if (_hasIndexedDB()) {
            _context33.n = 8;
            break;
          }
          if (!_isNodeRuntime()) {
            _context33.n = 7;
            break;
          }
          _context33.p = 4;
          _context33.n = 5;
          return _getNodeFSVFileStore(name);
        case 5:
          return _context33.a(2, _context33.v);
        case 6:
          _context33.p = 6;
          _t14 = _context33.v;
          console.error('Node filesystem VStore unavailable, falling back to in-memory VStore:', _t14);
          return _context33.a(2, _getMemoryVFileStore(name));
        case 7:
          console.warn('IndexedDB is not supported in this environment. Falling back to an in-memory VStore.');
          return _context33.a(2, _getMemoryVFileStore(name));
        case 8:
          return _context33.a(2, new Promise(function (resolve, reject) {
            var request = globalObj.indexedDB.open('nge-vstore-' + name, 1);
            request.onupgradeneeded = function (event) {
              var db = event.target.result;
              if (!db.objectStoreNames.contains("files")) {
                db.createObjectStore("files");
              }
            };
            request.onerror = function (event) {
              console.error('Error opening IndexedDB:', event.target.error);
              if (_isNodeRuntime()) {
                _getNodeFSVFileStore(name).then(resolve)["catch"](function (error) {
                  console.error('Node filesystem VStore unavailable, falling back to in-memory VStore:', error);
                  resolve(_getMemoryVFileStore(name));
                });
              } else {
                resolve(_getMemoryVFileStore(name));
              }
            };
            request.onsuccess = function (event) {
              var db = event.target.result;
              var vfileStore = {
                close: function close() {
                  db.close();
                },
                exists: function exists(path) {
                  return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee28() {
                    return _regenerator().w(function (_context28) {
                      while (1) switch (_context28.n) {
                        case 0:
                          return _context28.a(2, new Promise(function (resolve, reject) {
                            var transaction = db.transaction(["files"], 'readonly');
                            var store = transaction.objectStore("files");
                            var request = store.count(path);
                            var exists = false;
                            request.onsuccess = function () {
                              exists = request.result > 0;
                            };
                            transaction.oncomplete = function () {
                              return resolve(exists);
                            };
                            var fail = function fail(event) {
                              console.error('Error checking file existence:', event.target.error);
                              resolve(false);
                            };
                            transaction.onerror = fail;
                            transaction.onabort = fail;
                          }));
                      }
                    }, _callee28);
                  }))();
                },
                read: function read(path) {
                  return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee29() {
                    return _regenerator().w(function (_context29) {
                      while (1) switch (_context29.n) {
                        case 0:
                          return _context29.a(2, new Promise(function (resolve, reject) {
                            var transaction = db.transaction(["files"], 'readonly');
                            var store = transaction.objectStore("files");
                            var request = store.get(path);
                            var result = null;
                            request.onsuccess = function () {
                              var value = request.result;
                              result = value === undefined || value === null ? null : _u(value);
                            };
                            transaction.oncomplete = function () {
                              return resolve(result);
                            };
                            var fail = function fail(event) {
                              console.error('Error reading file:', event.target.error);
                              resolve(null);
                            };
                            transaction.onerror = fail;
                            transaction.onabort = fail;
                          }));
                      }
                    }, _callee29);
                  }))();
                },
                write: function write(path, data) {
                  return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee30() {
                    return _regenerator().w(function (_context30) {
                      while (1) switch (_context30.n) {
                        case 0:
                          return _context30.a(2, new Promise(function (resolve, reject) {
                            var transaction = db.transaction(["files"], 'readwrite');
                            var store = transaction.objectStore("files");
                            store.put(data, path);
                            transaction.oncomplete = function () {
                              return resolve();
                            };
                            var fail = function fail(event) {
                              console.error('Error writing file:', event.target.error);
                              reject(event.target.error || new Error("Error writing file: ".concat(path)));
                            };
                            transaction.onerror = fail;
                            transaction.onabort = fail;
                          }));
                      }
                    }, _callee30);
                  }))();
                },
                "delete": function _delete(path) {
                  return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee31() {
                    return _regenerator().w(function (_context31) {
                      while (1) switch (_context31.n) {
                        case 0:
                          return _context31.a(2, new Promise(function (resolve, reject) {
                            var transaction = db.transaction(["files"], 'readwrite');
                            var store = transaction.objectStore("files");
                            store["delete"](path);
                            transaction.oncomplete = function () {
                              return resolve();
                            };
                            var fail = function fail(event) {
                              console.error('Error deleting file:', event.target.error);
                              reject(event.target.error || new Error("Error deleting file: ".concat(path)));
                            };
                            transaction.onerror = fail;
                            transaction.onabort = fail;
                          }));
                      }
                    }, _callee31);
                  }))();
                },
                listAll: function listAll() {
                  return _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee32() {
                    return _regenerator().w(function (_context32) {
                      while (1) switch (_context32.n) {
                        case 0:
                          return _context32.a(2, new Promise(function (resolve, reject) {
                            try {
                              var transaction = db.transaction(["files"], 'readonly');
                              var store = transaction.objectStore("files");
                              var _request = store.getAllKeys();
                              var files = [];
                              _request.onsuccess = function (event) {
                                files = event.target.result || [];
                              };
                              transaction.oncomplete = function () {
                                return resolve(files);
                              };
                              var fail = function fail(event) {
                                console.error('Error listing files:', event.target.error);
                                resolve([]);
                              };
                              transaction.onerror = fail;
                              transaction.onabort = fail;
                            } catch (e) {
                              console.error('Error during listAll operation:', e);
                              resolve([]);
                            }
                          }));
                      }
                    }, _callee32);
                  }))();
                }
              };
              resolve(vfileStore);
            };
          }));
      }
    }, _callee33, null, [[4, 6]]);
  }));
  return _getVFileStore.apply(this, arguments);
}
var _vfilePendingCloses = new Map();
function _pendingVFileCloses(name, create) {
  var closes = _vfilePendingCloses.get(name);
  if (!closes && create) {
    closes = new Map();
    _vfilePendingCloses.set(name, closes);
  }
  return closes;
}
function _removePendingVFileClose(name, path, promise) {
  var closes = _pendingVFileCloses(name, false);
  if (!closes || closes.get(path) !== promise) {
    return;
  }
  closes["delete"](path);
  if (closes.size === 0) {
    _vfilePendingCloses["delete"](name);
  }
}
function _waitForPendingVFileClose(name, path) {
  var closes = _pendingVFileCloses(name, false);
  return (closes === null || closes === void 0 ? void 0 : closes.get(path)) || Promise.resolve();
}
function _waitForPendingVFileCloses(name) {
  var closes = _pendingVFileCloses(name, false);
  return closes ? Promise.all(Array.from(closes.values())) : Promise.resolve();
}
function _withVFileStore(_x4, _x5) {
  return _withVFileStore2.apply(this, arguments);
}
function _withVFileStore2() {
  _withVFileStore2 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee34(name, operation) {
    var vstore;
    return _regenerator().w(function (_context34) {
      while (1) switch (_context34.p = _context34.n) {
        case 0:
          _context34.n = 1;
          return getVFileStore(name);
        case 1:
          vstore = _context34.v;
          _context34.p = 2;
          _context34.n = 3;
          return operation(vstore);
        case 3:
          return _context34.a(2, _context34.v);
        case 4:
          _context34.p = 4;
          vstore.close();
          return _context34.f(4);
        case 5:
          return _context34.a(2);
      }
    }, _callee34, null, [[2,, 4, 5]]);
  }));
  return _withVFileStore2.apply(this, arguments);
}
var vfileExists = /*#__PURE__*/function () {
  var _ref6 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee16(name, path) {
    return _regenerator().w(function (_context16) {
      while (1) switch (_context16.n) {
        case 0:
          _context16.n = 1;
          return _waitForPendingVFileClose(name, path);
        case 1:
          return _context16.a(2, _withVFileStore(name, function (vstore) {
            return vstore.exists(path);
          }));
      }
    }, _callee16);
  }));
  return function vfileExists(_x6, _x7) {
    return _ref6.apply(this, arguments);
  };
}();
var vfileRead = /*#__PURE__*/function () {
  var _ref7 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee17(name, path) {
    var value;
    return _regenerator().w(function (_context17) {
      while (1) switch (_context17.n) {
        case 0:
          _context17.n = 1;
          return _waitForPendingVFileClose(name, path);
        case 1:
          _context17.n = 2;
          return _withVFileStore(name, function (vstore) {
            return vstore.read(path);
          });
        case 2:
          value = _context17.v;
          if (!(value === null || value === undefined)) {
            _context17.n = 3;
            break;
          }
          throw new Error("File not found: ".concat(path, " in store ").concat(name));
        case 3:
          return _context17.a(2, _u(value));
      }
    }, _callee17);
  }));
  return function vfileRead(_x8, _x9) {
    return _ref7.apply(this, arguments);
  };
}();
var vfileWrite = function vfileWrite(name, path, data) {
  // void
  var bytes = _u(data);
  var closes = _pendingVFileCloses(name, true);
  var previous = closes.get(path) || Promise.resolve();
  var current = previous["catch"](function () {
    return undefined;
  }).then(function () {
    return _withVFileStore(name, function (vstore) {
      return vstore.write(path, bytes);
    });
  });
  closes.set(path, current);
  current.then(function () {
    return _removePendingVFileClose(name, path, current);
  }, function () {
    return _removePendingVFileClose(name, path, current);
  });
  return current;
};
var vfileDelete = /*#__PURE__*/function () {
  var _ref8 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee18(name, path) {
    return _regenerator().w(function (_context18) {
      while (1) switch (_context18.n) {
        case 0:
          _context18.n = 1;
          return _waitForPendingVFileClose(name, path);
        case 1:
          return _context18.a(2, _withVFileStore(name, function (vstore) {
            return vstore["delete"](path);
          }));
      }
    }, _callee18);
  }));
  return function vfileDelete(_x0, _x1) {
    return _ref8.apply(this, arguments);
  };
}();
var vfileListAll = /*#__PURE__*/function () {
  var _ref9 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee19(name) {
    var files, _t9;
    return _regenerator().w(function (_context19) {
      while (1) switch (_context19.p = _context19.n) {
        case 0:
          _context19.p = 0;
          _context19.n = 1;
          return _waitForPendingVFileCloses(name);
        case 1:
          _context19.n = 2;
          return _withVFileStore(name, function (vstore) {
            return vstore.listAll();
          });
        case 2:
          files = _context19.v;
          if (!(files === undefined || files === null)) {
            _context19.n = 3;
            break;
          }
          console.warn("No files found in store ".concat(name));
          return _context19.a(2, []);
        case 3:
          return _context19.a(2, files.map(function (file) {
            return file.toString();
          }));
        case 4:
          _context19.p = 4;
          _t9 = _context19.v;
          console.error("Error listing files in store ".concat(name, ":"), _t9);
          return _context19.a(2, []);
      }
    }, _callee19, null, [[0, 4]]);
  }));
  return function vfileListAll(_x10) {
    return _ref9.apply(this, arguments);
  };
}();
var vfileExistsPromise = vfileExists;
var vfileReadPromise = /*#__PURE__*/function () {
  var _ref0 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee20(name, path) {
    var _t0;
    return _regenerator().w(function (_context20) {
      while (1) switch (_context20.n) {
        case 0:
          _t0 = _bw;
          _context20.n = 1;
          return vfileRead(name, path);
        case 1:
          return _context20.a(2, _t0(_context20.v));
      }
    }, _callee20);
  }));
  return function vfileReadPromise(_x11, _x12) {
    return _ref0.apply(this, arguments);
  };
}();
var vfileWritePromise = vfileWrite;
var vfileDeletePromise = vfileDelete;
var vfileListAllPromise = vfileListAll;
var vfileExistsAsync = function vfileExistsAsync(name, path, res, rej) {
  // void
  vfileExists(name, path).then(function (result) {
    return res(result);
  })["catch"](function (error) {
    console.error("Error checking file existence: ".concat(error));
    rej(String(error));
  });
};
var vfileReadAsync = function vfileReadAsync(name, path, res, rej) {
  // void
  vfileRead(name, path).then(function (result) {
    return res(_bw(result));
  })["catch"](function (error) {
    console.error("Error reading file: ".concat(error));
    rej(String(error));
  });
};
var vfileWriteAsync = function vfileWriteAsync(name, path, data, res, rej) {
  // void
  vfileWrite(name, path, data).then(function () {
    return res();
  })["catch"](function (error) {
    console.error("Error writing file: ".concat(error));
    rej(String(error));
  });
};
var vfileDeleteAsync = function vfileDeleteAsync(name, path, res, rej) {
  // void
  vfileDelete(name, path).then(function () {
    return res();
  })["catch"](function (error) {
    console.error("Error deleting file: ".concat(error));
    rej(String(error));
  });
};
var vfileListAllAsync = function vfileListAllAsync(name, res, rej) {
  // str[]
  vfileListAll(name).then(function (result) {
    res(result);
  })["catch"](function (error) {
    console.error("Error listing files: ".concat(error));
    rej(String(error));
  });
};
var getRuntimeName = function getRuntimeName() {
  // str
  var runtime = "runtime";
  if (typeof Capacitor !== 'undefined' && Capacitor && Capacitor.getPlatform) {
    runtime = "capacitor " + Capacitor.getPlatform();
  } else if (typeof self !== "undefined" && self.origin && self.origin.startsWith("capacitor:")) {
    runtime = "capacitor worker";
  } else if (_isNodeRuntime()) {
    runtime = "node " + _getNodeVersion();
  } else if (typeof window !== 'undefined') {
    runtime = "browser";
  }
  return runtime;
};
var getPlatformName = function getPlatformName() {
  // str
  return 'JavaScript (' + getRuntimeName() + ')';
};
function toFunction(f) {
  // Function
  var namespace = f.split('.');
  var obj = null;
  var fun = null;

  // Get the root object
  if (namespace[0] === 'window' || namespace[0] === 'globalThis' || namespace[0] === 'self') {
    obj = _s();
  } else {
    var globalObj = _s();
    obj = globalObj[namespace[0]];
  }
  if (!obj) {
    throw new Error("Root object ".concat(namespace[0], " is not defined"));
  }

  // Navigate to the parent object and function
  for (var i = 1; i < namespace.length - 1; i++) {
    if (!obj) {
      throw new Error("Object ".concat(namespace.slice(0, i + 1).join('.'), " is not defined"));
    }
    obj = obj[namespace[i]];
  }

  // Get the final function
  var functionName = namespace[namespace.length - 1];
  fun = obj[functionName];
  if (!fun) {
    throw new Error("Function ".concat(functionName, " is not defined"));
  }
  if (typeof fun !== 'function') {
    throw new Error("".concat(functionName, " is not a function"));
  }

  // Return a bound function to preserve the 'this' context
  return fun.bind(obj);
}
var callFunction = /*#__PURE__*/function () {
  var _ref1 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee21(functionName, data, res, rej) {
    var _s8, args, executor, result, _t1;
    return _regenerator().w(function (_context21) {
      while (1) switch (_context21.p = _context21.n) {
        case 0:
          _context21.p = 0;
          args = JSON.parse(data).args;
          executor = (_s8 = _s()) === null || _s8 === void 0 ? void 0 : _s8.ngeFunctionExecutor;
          if (!(typeof executor === 'function')) {
            _context21.n = 2;
            break;
          }
          _context21.n = 1;
          return executor(functionName, args);
        case 1:
          result = _context21.v;
          _context21.n = 6;
          break;
        case 2:
          if (!(executor && typeof executor.execute === 'function')) {
            _context21.n = 4;
            break;
          }
          _context21.n = 3;
          return executor.execute(functionName, args);
        case 3:
          result = _context21.v;
          _context21.n = 6;
          break;
        case 4:
          _context21.n = 5;
          return toFunction(functionName).apply(void 0, _toConsumableArray(args));
        case 5:
          result = _context21.v;
        case 6:
          res(JSON.stringify({
            result: result
          }));
          _context21.n = 8;
          break;
        case 7:
          _context21.p = 7;
          _t1 = _context21.v;
          console.error("Error executing function ".concat(functionName, ":"), _t1);
          rej(String(_t1));
        case 8:
          return _context21.a(2);
      }
    }, _callee21, null, [[0, 7]]);
  }));
  return function callFunction(_x13, _x14, _x15, _x16) {
    return _ref1.apply(this, arguments);
  };
}();
var callFunctionPromise = /*#__PURE__*/function () {
  var _ref10 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee22(functionName, data) {
    var _s9;
    var args, executor, result;
    return _regenerator().w(function (_context22) {
      while (1) switch (_context22.n) {
        case 0:
          args = JSON.parse(data).args;
          executor = (_s9 = _s()) === null || _s9 === void 0 ? void 0 : _s9.ngeFunctionExecutor;
          if (!(typeof executor === 'function')) {
            _context22.n = 2;
            break;
          }
          _context22.n = 1;
          return executor(functionName, args);
        case 1:
          result = _context22.v;
          _context22.n = 6;
          break;
        case 2:
          if (!(executor && typeof executor.execute === 'function')) {
            _context22.n = 4;
            break;
          }
          _context22.n = 3;
          return executor.execute(functionName, args);
        case 3:
          result = _context22.v;
          _context22.n = 6;
          break;
        case 4:
          _context22.n = 5;
          return toFunction(functionName).apply(void 0, _toConsumableArray(args));
        case 5:
          result = _context22.v;
        case 6:
          return _context22.a(2, JSON.stringify({
            result: result
          }));
      }
    }, _callee22);
  }));
  return function callFunctionPromise(_x17, _x18) {
    return _ref10.apply(this, arguments);
  };
}();
var canCallFunction = /*#__PURE__*/function () {
  var _ref11 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee23(functionName, res) {
    var _s0, executor, canCall, _t10;
    return _regenerator().w(function (_context23) {
      while (1) switch (_context23.p = _context23.n) {
        case 0:
          _context23.p = 0;
          executor = (_s0 = _s()) === null || _s0 === void 0 ? void 0 : _s0.ngeFunctionExecutor;
          if (!(executor && typeof executor.canExecute === 'function')) {
            _context23.n = 2;
            break;
          }
          _context23.n = 1;
          return executor.canExecute(functionName);
        case 1:
          canCall = !!_context23.v;
          _context23.n = 3;
          break;
        case 2:
          if (typeof executor === 'function' || executor && typeof executor.execute === 'function') {
            canCall = true;
          } else {
            canCall = !!toFunction(functionName);
          }
        case 3:
          if (canCall) {
            console.log("Function ".concat(functionName, " can be called:"), canCall);
            res(true);
          } else {
            console.warn("Function ".concat(functionName, " cannot be called."));
            res(false);
          }
          _context23.n = 5;
          break;
        case 4:
          _context23.p = 4;
          _t10 = _context23.v;
          console.error("Error checking function ".concat(functionName, ":"), _t10);
          res(false);
        case 5:
          return _context23.a(2);
      }
    }, _callee23, null, [[0, 4]]);
  }));
  return function canCallFunction(_x19, _x20) {
    return _ref11.apply(this, arguments);
  };
}();
var canCallFunctionPromise = /*#__PURE__*/function () {
  var _ref12 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee24(functionName) {
    var _s1;
    var executor, _t11;
    return _regenerator().w(function (_context24) {
      while (1) switch (_context24.p = _context24.n) {
        case 0:
          executor = (_s1 = _s()) === null || _s1 === void 0 ? void 0 : _s1.ngeFunctionExecutor;
          if (!(executor && typeof executor.canExecute === 'function')) {
            _context24.n = 2;
            break;
          }
          _context24.n = 1;
          return executor.canExecute(functionName);
        case 1:
          return _context24.a(2, !!_context24.v);
        case 2:
          if (!(typeof executor === 'function' || executor && typeof executor.execute === 'function')) {
            _context24.n = 3;
            break;
          }
          return _context24.a(2, true);
        case 3:
          _context24.p = 3;
          return _context24.a(2, !!toFunction(functionName));
        case 4:
          _context24.p = 4;
          _t11 = _context24.v;
          return _context24.a(2, false);
      }
    }, _callee24, null, [[3, 4]]);
  }));
  return function canCallFunctionPromise(_x21) {
    return _ref12.apply(this, arguments);
  };
}();
var openURL = function openURL(url) {
  // void
  try {
    var globalObj = _s();
    if (globalObj && typeof globalObj.ngeOpenURL === 'function') {
      globalObj.ngeOpenURL(url);
    } else if (globalObj && globalObj.open) {
      globalObj.open(url, '_blank');
    } else {
      console.warn('Cannot open URL: No suitable global object found.');
    }
  } catch (error) {
    console.error('Error opening URL:', error);
  }
};
var nfkc = function nfkc(str) {
  // str
  if (str && str.normalize) {
    return str.normalize('NFKC');
  } else {
    console.warn('String normalization not supported in this environment.');
    return str;
  }
};
var scryptAsync = function scryptAsync(p, /*byte[]*/
s, /*byte[]*/
n, /*int*/
r, /*int*/
p2, /*int*/
dkLen, /*int*/
res, rej) {
  // Uint8Array byte[]
  (0,_noble_hashes_scrypt__WEBPACK_IMPORTED_MODULE_8__.scryptAsync)(_u(p), _u(s), {
    N: n,
    r: r,
    p: p2,
    dkLen: dkLen
  }).then(function (result) {
    res(_bw(result));
  })["catch"](function (error) {
    console.error("Error in scryptAsync: ".concat(error));
    rej(String(error));
  });
};
var scryptBufferPromise = /*#__PURE__*/function () {
  var _ref13 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee25(password, salt, n, r, p, dkLen, output) {
    var derived;
    return _regenerator().w(function (_context25) {
      while (1) switch (_context25.n) {
        case 0:
          _context25.n = 1;
          return (0,_noble_hashes_scrypt__WEBPACK_IMPORTED_MODULE_8__.scryptAsync)(_u(password), _u(salt), {
            N: n,
            r: r,
            p: p,
            dkLen: dkLen
          });
        case 1:
          derived = _context25.v;
          return _context25.a(2, _writeBytes(output, derived));
      }
    }, _callee25);
  }));
  return function scryptBufferPromise(_x22, _x23, _x24, _x25, _x26, _x27, _x28) {
    return _ref13.apply(this, arguments);
  };
}();
var xchacha20poly1305 = function xchacha20poly1305(key, /*byte[]*/
nonce, /*byte[]*/
data, /*byte[]*/
associatedData, /*byte[]*/
forEncryption /*bool*/) {
  // Uint8Array byte[]
  // let xc2p1 = xchacha20poly1305(key, nonce, aad)
  key = _u(key);
  nonce = _u(nonce);
  data = _u(data);
  associatedData = _u(associatedData);
  var cipher = (0,_noble_ciphers_chacha_js__WEBPACK_IMPORTED_MODULE_0__.xchacha20poly1305)(key, nonce, associatedData);
  if (forEncryption) {
    return _u(cipher.encrypt(data));
  } else {
    return _u(cipher.decrypt(data));
  }
};
var xchacha20poly1305Buffer = function xchacha20poly1305Buffer(key, nonce, data, associatedData, forEncryption, output) {
  return _writeBytes(output, xchacha20poly1305(key, nonce, data, associatedData, forEncryption));
};
var rtcSetLocalDescriptionAsync = function rtcSetLocalDescriptionAsync(conn /*RTCPeerConnection*/, sdp /*str*/, type /*str*/, res, rej) {
  // void
  conn.setLocalDescription({
    type: type,
    sdp: sdp
  }).then(function () {
    return res();
  })["catch"](function (error) {
    console.error('Error setting local description:', error);
    rej(String(error));
  });
};
var rtcSetLocalDescriptionPromise = function rtcSetLocalDescriptionPromise(conn, sdp, type) {
  return conn.setLocalDescription({
    type: type,
    sdp: sdp
  });
};
var rtcSetRemoteDescriptionPromise = function rtcSetRemoteDescriptionPromise(conn, sdp, type) {
  return conn.setRemoteDescription({
    type: type,
    sdp: sdp
  });
};
var rtcAddIceCandidatePromise = function rtcAddIceCandidatePromise(conn, candidate) {
  return conn.addIceCandidate(candidate);
};
var rtcCreateAnswerPromise = function rtcCreateAnswerPromise(conn) {
  return conn.createAnswer();
};
var rtcCreateOfferPromise = function rtcCreateOfferPromise(conn) {
  return conn.createOffer();
};
var rtcInitPeerEventQueue = function rtcInitPeerEventQueue(conn) {
  if (conn._ngeEventQueueState && !conn._ngeEventQueueState.disposed) {
    return;
  }
  _initEventQueue(conn);
  conn.addEventListener('icecandidate', function (event) {
    if (event.candidate) {
      _enqueueEvent(conn, {
        type: 1,
        candidate: event.candidate
      });
    }
  });
  conn.addEventListener('iceconnectionstatechange', function () {
    _enqueueEvent(conn, {
      type: 2,
      state: conn.iceConnectionState
    });
  });
  conn.addEventListener('connectionstatechange', function () {
    _enqueueEvent(conn, {
      type: 3,
      state: conn.connectionState
    });
  });
  conn.addEventListener('datachannel', function (event) {
    _enqueueEvent(conn, {
      type: 4,
      channel: event.channel
    });
  });
};
var rtcPeerEventType = function rtcPeerEventType(conn) {
  var _currentEvent$type2, _currentEvent5;
  return (_currentEvent$type2 = (_currentEvent5 = _currentEvent(conn)) === null || _currentEvent5 === void 0 ? void 0 : _currentEvent5.type) !== null && _currentEvent$type2 !== void 0 ? _currentEvent$type2 : 0;
};
var rtcPeerEventCandidate = function rtcPeerEventCandidate(conn) {
  var _currentEvent$candida, _currentEvent6;
  return (_currentEvent$candida = (_currentEvent6 = _currentEvent(conn)) === null || _currentEvent6 === void 0 ? void 0 : _currentEvent6.candidate) !== null && _currentEvent$candida !== void 0 ? _currentEvent$candida : null;
};
var rtcPeerEventState = function rtcPeerEventState(conn) {
  var _currentEvent$state, _currentEvent7;
  return (_currentEvent$state = (_currentEvent7 = _currentEvent(conn)) === null || _currentEvent7 === void 0 ? void 0 : _currentEvent7.state) !== null && _currentEvent$state !== void 0 ? _currentEvent$state : null;
};
var rtcPeerEventChannel = function rtcPeerEventChannel(conn) {
  var _currentEvent$channel, _currentEvent8;
  return (_currentEvent$channel = (_currentEvent8 = _currentEvent(conn)) === null || _currentEvent8 === void 0 ? void 0 : _currentEvent8.channel) !== null && _currentEvent$channel !== void 0 ? _currentEvent$channel : null;
};
var rtcPeerConsumeEvent = function rtcPeerConsumeEvent(conn) {
  var _conn$_ngeEventQueueS;
  (_conn$_ngeEventQueueS = conn._ngeEventQueueState) === null || _conn$_ngeEventQueueS === void 0 || (_conn$_ngeEventQueueS = _conn$_ngeEventQueueS.queue) === null || _conn$_ngeEventQueueS === void 0 || _conn$_ngeEventQueueS.shift();
};
var rtcInitDataChannelEventQueue = function rtcInitDataChannelEventQueue(channel) {
  if (channel._ngeEventQueueState && !channel._ngeEventQueueState.disposed) {
    return;
  }
  _initEventQueue(channel);
  channel.binaryType = 'arraybuffer';
  channel.addEventListener('open', function () {
    return _enqueueEvent(channel, {
      type: 1
    });
  });
  channel.addEventListener('close', function () {
    return _enqueueEvent(channel, {
      type: 2
    });
  });
  channel.addEventListener('error', function (event) {
    var _event$error;
    _enqueueEvent(channel, {
      type: 3,
      error: String((_event$error = event === null || event === void 0 ? void 0 : event.error) !== null && _event$error !== void 0 ? _event$error : 'RTC data channel error')
    });
  });
  channel.addEventListener('bufferedamountlow', function () {
    return _enqueueEvent(channel, {
      type: 4
    });
  });
  channel.addEventListener('message', function (event) {
    _enqueueEvent(channel, {
      type: 5,
      binary: _u(event.data)
    });
  });
};
var rtcDataChannelEventType = function rtcDataChannelEventType(channel) {
  var _currentEvent$type3, _currentEvent9;
  return (_currentEvent$type3 = (_currentEvent9 = _currentEvent(channel)) === null || _currentEvent9 === void 0 ? void 0 : _currentEvent9.type) !== null && _currentEvent$type3 !== void 0 ? _currentEvent$type3 : 0;
};
var rtcDataChannelEventError = function rtcDataChannelEventError(channel) {
  var _currentEvent$error, _currentEvent0;
  return (_currentEvent$error = (_currentEvent0 = _currentEvent(channel)) === null || _currentEvent0 === void 0 ? void 0 : _currentEvent0.error) !== null && _currentEvent$error !== void 0 ? _currentEvent$error : null;
};
var rtcDataChannelEventBinaryLength = function rtcDataChannelEventBinaryLength(channel) {
  var _currentEvent$binary$2, _currentEvent1;
  return (_currentEvent$binary$2 = (_currentEvent1 = _currentEvent(channel)) === null || _currentEvent1 === void 0 || (_currentEvent1 = _currentEvent1.binary) === null || _currentEvent1 === void 0 ? void 0 : _currentEvent1.byteLength) !== null && _currentEvent$binary$2 !== void 0 ? _currentEvent$binary$2 : 0;
};
var rtcReadDataChannelBinaryEvent = function rtcReadDataChannelBinaryEvent(channel, output) {
  var event = _currentEvent(channel);
  if (!event || event.type !== 5) {
    throw new Error('Current RTC data channel event is not binary');
  }
  return _writeBytes(output, event.binary);
};
var rtcDataChannelConsumeEvent = function rtcDataChannelConsumeEvent(channel) {
  var _channel$_ngeEventQue;
  (_channel$_ngeEventQue = channel._ngeEventQueueState) === null || _channel$_ngeEventQue === void 0 || (_channel$_ngeEventQue = _channel$_ngeEventQue.queue) === null || _channel$_ngeEventQue === void 0 || _channel$_ngeEventQue.shift();
};
var rtcSetRemoteDescriptionAsync = function rtcSetRemoteDescriptionAsync(conn /*RTCPeerConnection*/, sdp /*str*/, type /*str*/, res, rej) {
  // void
  conn.setRemoteDescription({
    type: type,
    sdp: sdp
  }).then(function () {
    return res();
  })["catch"](function (error) {
    console.error('Error setting remote description:', error);
    rej(String(error));
  });
};
var rtcAddIceCandidateAsync = function rtcAddIceCandidateAsync(conn /*RTCPeerConnection*/, candidate /*RTCIceCandidate*/, res, rej) {
  // void
  conn.addIceCandidate(candidate).then(function () {
    return res();
  })["catch"](function (error) {
    console.error('Error adding ICE candidate:', error);
    rej(String(error));
  });
};
var rtcCreateAnswerAsync = function rtcCreateAnswerAsync(conn /*RTCPeerConnection*/, res, rej) {
  // str
  conn.createAnswer().then(function (answer) {
    return res(answer);
  })["catch"](function (error) {
    console.error('Error creating answer:', error);
    rej(String(error));
  });
};
var rtcCreateOfferAsync = function rtcCreateOfferAsync(conn /*RTCPeerConnection*/, res, rej) {
  // str
  conn.createOffer().then(function (offer) {
    return res(offer);
  })["catch"](function (error) {
    console.error('Error creating offer:', error);
    rej(String(error));
  });
};
var rtcCreatePeerConnection = function rtcCreatePeerConnection(urls /*str[]*/) {
  // RTCPeerConnection
  var conf = {
    iceServers: urls.map(function (url) {
      return {
        urls: url
      };
    })
  };
  var RTCPeerConnectionCtor = _getRTCPeerConnectionCtor();
  var conn = new RTCPeerConnectionCtor(conf);
  return conn;
};
var rtcCreateDataChannel = function rtcCreateDataChannel(conn /*RTCPeerConnection*/, label /*str*/, protocol /*str*/, ordered /*bool*/, reliable /*bool*/, maxRetransmits /*int*/, maxPacketLifeTimeMs /*int*/) {
  // RTCDataChannel
  var options = {
    ordered: !!ordered
  };
  if (protocol !== null && protocol !== undefined) {
    options.protocol = String(protocol);
  }
  var hasMaxRetransmits = Number(maxRetransmits) >= 0;
  var hasMaxPacketLifeTime = Number(maxPacketLifeTimeMs) >= 0;
  if (hasMaxRetransmits) {
    options.maxRetransmits = Number(maxRetransmits);
  }
  if (hasMaxPacketLifeTime) {
    options.maxPacketLifeTime = Number(maxPacketLifeTimeMs);
  }

  // Browser WebRTC has no standalone "reliable=false" switch.
  if (!reliable && !hasMaxRetransmits && !hasMaxPacketLifeTime) {
    options.maxRetransmits = 0;
  }
  return conn.createDataChannel(label, options);
};
var rtcCreateIceCandidate = function rtcCreateIceCandidate(sdp /*str*/, spdMid /*str*/) {
  // RTCIceCandidate
  var RTCIceCandidateCtor = _getRTCIceCandidateCtor();
  return new RTCIceCandidateCtor({
    candidate: sdp,
    sdpMid: spdMid,
    sdpMLineIndex: null
  });
};
var rtcDataChannelGetProtocol = function rtcDataChannelGetProtocol(channel) {
  var _channel$protocol;
  return (_channel$protocol = channel === null || channel === void 0 ? void 0 : channel.protocol) !== null && _channel$protocol !== void 0 ? _channel$protocol : '';
};
var rtcDataChannelIsOrdered = function rtcDataChannelIsOrdered(channel) {
  return (channel === null || channel === void 0 ? void 0 : channel.ordered) !== false;
};
var rtcDataChannelIsReliable = function rtcDataChannelIsReliable(channel) {
  return (channel === null || channel === void 0 ? void 0 : channel.maxRetransmits) == null && (channel === null || channel === void 0 ? void 0 : channel.maxPacketLifeTime) == null;
};
var rtcDataChannelGetMaxRetransmits = function rtcDataChannelGetMaxRetransmits(channel) {
  return (channel === null || channel === void 0 ? void 0 : channel.maxRetransmits) == null ? -1 : Number(channel.maxRetransmits);
};
var rtcDataChannelGetMaxPacketLifeTime = function rtcDataChannelGetMaxPacketLifeTime(channel) {
  return (channel === null || channel === void 0 ? void 0 : channel.maxPacketLifeTime) == null ? -1 : Number(channel.maxPacketLifeTime);
};
var rtcGetMaxMessageSize = function rtcGetMaxMessageSize(conn) {
  var _conn$sctp;
  var v = conn === null || conn === void 0 || (_conn$sctp = conn.sctp) === null || _conn$sctp === void 0 ? void 0 : _conn$sctp.maxMessageSize;
  return Number.isFinite(v) ? Number(v) : -1;
};
var rtcDataChannelGetBufferedAmount = function rtcDataChannelGetBufferedAmount(channel) {
  var v = channel === null || channel === void 0 ? void 0 : channel.bufferedAmount;
  return Number.isFinite(v) ? Number(v) : 0;
};
var rtcDataChannelGetAvailableAmount = function rtcDataChannelGetAvailableAmount(conn, channel) {
  var max = rtcGetMaxMessageSize(conn);
  if (!Number.isFinite(max) || max < 0) {
    return -1;
  }
  return Math.max(0, Number(max) - rtcDataChannelGetBufferedAmount(channel));
};
var rtcDataChannelSetBufferedAmountLowThreshold = function rtcDataChannelSetBufferedAmountLowThreshold(channel, threshold) {
  channel.bufferedAmountLowThreshold = Number(threshold);
};
var fetchPromise = function fetchPromise(method, url, headers, body, timeoutMs) {
  var _s10, _s11;
  var controller = new AbortController();
  var timeoutId = TeaVMBinds_setTimeout(function () {
    return controller.abort();
  }, timeoutMs);
  var options = {
    method: method,
    headers: headers ? JSON.parse(headers) : {},
    signal: controller.signal
  };
  if (body && method !== 'GET' && method !== 'HEAD') {
    options.body = _u(body);
  }
  var fetchImpl = ((_s10 = _s()) === null || _s10 === void 0 ? void 0 : _s10.ngeFetch) || ((_s11 = _s()) === null || _s11 === void 0 ? void 0 : _s11.fetch);
  if (typeof fetchImpl !== 'function') {
    clearTimeout(timeoutId);
    return Promise.reject(new Error('Fetch is not available in this runtime'));
  }
  return fetchImpl.call(_s(), url, options).then(/*#__PURE__*/function () {
    var _ref14 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee26(response) {
      var respHeaders, respBody, _t12, _t13;
      return _regenerator().w(function (_context26) {
        while (1) switch (_context26.n) {
          case 0:
            clearTimeout(timeoutId);
            respHeaders = {};
            response.headers.forEach(function (value, key) {
              respHeaders[key] = value;
            });
            _t12 = Uint8Array;
            _context26.n = 1;
            return response.arrayBuffer();
          case 1:
            _t13 = _context26.v;
            respBody = new _t12(_t13);
            return _context26.a(2, {
              status: response.status,
              statusText: response.statusText,
              headers: JSON.stringify(respHeaders),
              body: respBody
            });
        }
      }, _callee26);
    }));
    return function (_x29) {
      return _ref14.apply(this, arguments);
    };
  }())["catch"](function (error) {
    clearTimeout(timeoutId);
    throw error;
  });
};
var fetchAsync = function fetchAsync(method, url, headers, body, timeoutMs, res, rej) {
  fetchPromise(method, url, headers, body, timeoutMs).then(function (response) {
    return res(response.status, response.headers, response.body);
  })["catch"](function (error) {
    return rej(String(error));
  });
};
var fetchBufferAsync = fetchAsync;
var fetchBufferPromise = fetchPromise;
var _httpResponseBody = function _httpResponseBody(response) {
  if (!response || response.body == null) {
    return new Uint8Array(0);
  }
  return _u(response.body);
};
var httpResponseBodyLength = function httpResponseBodyLength(response) {
  return _httpResponseBody(response).byteLength;
};
var copyHttpResponseBody = function copyHttpResponseBody(response, output) {
  return _writeBytes(output, _httpResponseBody(response));
};
var fetchStreamPromise = function fetchStreamPromise(method, url, headers, body, timeoutMs) {
  var _s12, _s13;
  var controller = new AbortController();
  var timeoutId = TeaVMBinds_setTimeout(function () {
    return controller.abort();
  }, timeoutMs);
  var options = {
    method: method,
    headers: headers ? JSON.parse(headers) : {},
    signal: controller.signal,
    redirect: 'error'
  };
  if (body && method !== 'GET' && method !== 'HEAD') {
    options.body = _u(body);
  }
  var fetchImpl = ((_s12 = _s()) === null || _s12 === void 0 ? void 0 : _s12.ngeFetch) || ((_s13 = _s()) === null || _s13 === void 0 ? void 0 : _s13.fetch);
  if (typeof fetchImpl !== 'function') {
    clearTimeout(timeoutId);
    return Promise.reject(new Error('Fetch is not available in this runtime'));
  }
  return fetchImpl.call(_s(), url, options).then(/*#__PURE__*/function () {
    var _ref15 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee27(response) {
      var respHeaders, stream;
      return _regenerator().w(function (_context27) {
        while (1) switch (_context27.n) {
          case 0:
            clearTimeout(timeoutId);
            respHeaders = {};
            response.headers.forEach(function (value, key) {
              respHeaders[key] = value;
            });
            stream = response.body;
            return _context27.a(2, {
              status: response.status,
              statusText: response.statusText,
              headers: JSON.stringify(respHeaders),
              body: stream
            });
        }
      }, _callee27);
    }));
    return function (_x30) {
      return _ref15.apply(this, arguments);
    };
  }())["catch"](function (error) {
    clearTimeout(timeoutId);
    throw error;
  });
};
var fetchStreamAsync = function fetchStreamAsync(method, url, headers, body, timeoutMs, res, rej) {
  fetchStreamPromise(method, url, headers, body, timeoutMs).then(function (response) {
    return res(response.status, response.headers, response.body);
  })["catch"](function (error) {
    return rej(String(error));
  });
};
var newPromise = function newPromise() {
  var res, rej;
  var p = new Promise(function (resolve, reject) {
    res = resolve;
    rej = reject;
  }).then(function () {})["catch"](function (e) {});
  return {
    promise: p,
    resolve: res,
    reject: rej,
    done: false
  };
};
var resolvePromise = function resolvePromise(handle) {
  handle.done = true;
  handle.resolve();
};
var rejectPromise = function rejectPromise(handle) {
  handle.done = true;
  handle.reject(new Error('Promise rejected'));
};
var getPromise = function getPromise(handle) {
  return handle.promise;
};
var rtcSetOnMessageHandler = function rtcSetOnMessageHandler(channel, callback) {
  // void
  channel.onmessage = function (event) {
    var data = event.data;
    callback(_u(data));
  };
};
var panic = function panic(err) {
  // void
  var message = 'PANIC: ' + err;
  if (_isNodeRuntime()) {
    try {
      if (process && process.stderr && typeof process.stderr.write === 'function') {
        process.stderr.write(message + '\n');
      } else {
        console.error(message);
      }
    } catch (stderrError) {
      console.error('Failed to write panic message to stderr:', stderrError);
    }
    if (process && typeof process.exit === 'function') {
      process.exit(1);
      return;
    }
    throw new Error(message);
  }
  console.error(message);
  if (typeof alert === 'function') {
    alert(message);
  }
  // try to forcefully kill the script
  if (typeof window !== 'undefined' && window.location) {
    window.location.reload();
  } else if (typeof self !== 'undefined' && self.close) {
    self.close();
  } else {
    throw new Error('PANIC: ' + err);
  }
};
const __webpack_exports___bw = __webpack_exports__._bw;
const __webpack_exports__aes256cbc = __webpack_exports__.aes256cbc;
const __webpack_exports__aes256cbcBuffer = __webpack_exports__.aes256cbcBuffer;
const __webpack_exports__base64decode = __webpack_exports__.base64decode;
const __webpack_exports__base64decodeBuffer = __webpack_exports__.base64decodeBuffer;
const __webpack_exports__base64encode = __webpack_exports__.base64encode;
const __webpack_exports__base64encodeBuffer = __webpack_exports__.base64encodeBuffer;
const __webpack_exports__callFunction = __webpack_exports__.callFunction;
const __webpack_exports__callFunctionPromise = __webpack_exports__.callFunctionPromise;
const __webpack_exports__canCallFunction = __webpack_exports__.canCallFunction;
const __webpack_exports__canCallFunctionPromise = __webpack_exports__.canCallFunctionPromise;
const __webpack_exports__chacha20 = __webpack_exports__.chacha20;
const __webpack_exports__chacha20Buffer = __webpack_exports__.chacha20Buffer;
const __webpack_exports__copyHttpResponseBody = __webpack_exports__.copyHttpResponseBody;
const __webpack_exports__delayPromise = __webpack_exports__.delayPromise;
const __webpack_exports__eventQueueDispose = __webpack_exports__.eventQueueDispose;
const __webpack_exports__eventQueueWaitPromise = __webpack_exports__.eventQueueWaitPromise;
const __webpack_exports__fetchAsync = __webpack_exports__.fetchAsync;
const __webpack_exports__fetchBufferAsync = __webpack_exports__.fetchBufferAsync;
const __webpack_exports__fetchBufferPromise = __webpack_exports__.fetchBufferPromise;
const __webpack_exports__fetchPromise = __webpack_exports__.fetchPromise;
const __webpack_exports__fetchStreamAsync = __webpack_exports__.fetchStreamAsync;
const __webpack_exports__fetchStreamPromise = __webpack_exports__.fetchStreamPromise;
const __webpack_exports__fromJSON = __webpack_exports__.fromJSON;
const __webpack_exports__genPubKey = __webpack_exports__.genPubKey;
const __webpack_exports__genPubKeyBuffer = __webpack_exports__.genPubKeyBuffer;
const __webpack_exports__generatePrivateKey = __webpack_exports__.generatePrivateKey;
const __webpack_exports__generatePrivateKeyBuffer = __webpack_exports__.generatePrivateKeyBuffer;
const __webpack_exports__getBundledResource = __webpack_exports__.getBundledResource;
const __webpack_exports__getClipboardContentAsync = __webpack_exports__.getClipboardContentAsync;
const __webpack_exports__getClipboardContentPromise = __webpack_exports__.getClipboardContentPromise;
const __webpack_exports__getPlatformName = __webpack_exports__.getPlatformName;
const __webpack_exports__getPromise = __webpack_exports__.getPromise;
const __webpack_exports__getRuntimeName = __webpack_exports__.getRuntimeName;
const __webpack_exports__hasBundledResource = __webpack_exports__.hasBundledResource;
const __webpack_exports__hkdfExpandBuffer = __webpack_exports__.hkdfExpandBuffer;
const __webpack_exports__hkdfExtractBuffer = __webpack_exports__.hkdfExtractBuffer;
const __webpack_exports__hkdf_expand = __webpack_exports__.hkdf_expand;
const __webpack_exports__hkdf_extract = __webpack_exports__.hkdf_extract;
const __webpack_exports__hmac = __webpack_exports__.hmac;
const __webpack_exports__hmacBuffer = __webpack_exports__.hmacBuffer;
const __webpack_exports__httpResponseBodyLength = __webpack_exports__.httpResponseBodyLength;
const __webpack_exports__newPromise = __webpack_exports__.newPromise;
const __webpack_exports__nfkc = __webpack_exports__.nfkc;
const __webpack_exports__openURL = __webpack_exports__.openURL;
const __webpack_exports__panic = __webpack_exports__.panic;
const __webpack_exports__randomBytes = __webpack_exports__.randomBytes;
const __webpack_exports__randomBytesBuffer = __webpack_exports__.randomBytesBuffer;
const __webpack_exports__rejectPromise = __webpack_exports__.rejectPromise;
const __webpack_exports__resolvePromise = __webpack_exports__.resolvePromise;
const __webpack_exports__rtcAddIceCandidateAsync = __webpack_exports__.rtcAddIceCandidateAsync;
const __webpack_exports__rtcAddIceCandidatePromise = __webpack_exports__.rtcAddIceCandidatePromise;
const __webpack_exports__rtcCreateAnswerAsync = __webpack_exports__.rtcCreateAnswerAsync;
const __webpack_exports__rtcCreateAnswerPromise = __webpack_exports__.rtcCreateAnswerPromise;
const __webpack_exports__rtcCreateDataChannel = __webpack_exports__.rtcCreateDataChannel;
const __webpack_exports__rtcCreateIceCandidate = __webpack_exports__.rtcCreateIceCandidate;
const __webpack_exports__rtcCreateOfferAsync = __webpack_exports__.rtcCreateOfferAsync;
const __webpack_exports__rtcCreateOfferPromise = __webpack_exports__.rtcCreateOfferPromise;
const __webpack_exports__rtcCreatePeerConnection = __webpack_exports__.rtcCreatePeerConnection;
const __webpack_exports__rtcDataChannelConsumeEvent = __webpack_exports__.rtcDataChannelConsumeEvent;
const __webpack_exports__rtcDataChannelEventBinaryLength = __webpack_exports__.rtcDataChannelEventBinaryLength;
const __webpack_exports__rtcDataChannelEventError = __webpack_exports__.rtcDataChannelEventError;
const __webpack_exports__rtcDataChannelEventType = __webpack_exports__.rtcDataChannelEventType;
const __webpack_exports__rtcDataChannelGetAvailableAmount = __webpack_exports__.rtcDataChannelGetAvailableAmount;
const __webpack_exports__rtcDataChannelGetBufferedAmount = __webpack_exports__.rtcDataChannelGetBufferedAmount;
const __webpack_exports__rtcDataChannelGetMaxPacketLifeTime = __webpack_exports__.rtcDataChannelGetMaxPacketLifeTime;
const __webpack_exports__rtcDataChannelGetMaxRetransmits = __webpack_exports__.rtcDataChannelGetMaxRetransmits;
const __webpack_exports__rtcDataChannelGetProtocol = __webpack_exports__.rtcDataChannelGetProtocol;
const __webpack_exports__rtcDataChannelIsOrdered = __webpack_exports__.rtcDataChannelIsOrdered;
const __webpack_exports__rtcDataChannelIsReliable = __webpack_exports__.rtcDataChannelIsReliable;
const __webpack_exports__rtcDataChannelSetBufferedAmountLowThreshold = __webpack_exports__.rtcDataChannelSetBufferedAmountLowThreshold;
const __webpack_exports__rtcGetMaxMessageSize = __webpack_exports__.rtcGetMaxMessageSize;
const __webpack_exports__rtcInitDataChannelEventQueue = __webpack_exports__.rtcInitDataChannelEventQueue;
const __webpack_exports__rtcInitPeerEventQueue = __webpack_exports__.rtcInitPeerEventQueue;
const __webpack_exports__rtcPeerConsumeEvent = __webpack_exports__.rtcPeerConsumeEvent;
const __webpack_exports__rtcPeerEventCandidate = __webpack_exports__.rtcPeerEventCandidate;
const __webpack_exports__rtcPeerEventChannel = __webpack_exports__.rtcPeerEventChannel;
const __webpack_exports__rtcPeerEventState = __webpack_exports__.rtcPeerEventState;
const __webpack_exports__rtcPeerEventType = __webpack_exports__.rtcPeerEventType;
const __webpack_exports__rtcReadDataChannelBinaryEvent = __webpack_exports__.rtcReadDataChannelBinaryEvent;
const __webpack_exports__rtcSetLocalDescriptionAsync = __webpack_exports__.rtcSetLocalDescriptionAsync;
const __webpack_exports__rtcSetLocalDescriptionPromise = __webpack_exports__.rtcSetLocalDescriptionPromise;
const __webpack_exports__rtcSetOnMessageHandler = __webpack_exports__.rtcSetOnMessageHandler;
const __webpack_exports__rtcSetRemoteDescriptionAsync = __webpack_exports__.rtcSetRemoteDescriptionAsync;
const __webpack_exports__rtcSetRemoteDescriptionPromise = __webpack_exports__.rtcSetRemoteDescriptionPromise;
const __webpack_exports__scryptAsync = __webpack_exports__.scryptAsync;
const __webpack_exports__scryptBufferPromise = __webpack_exports__.scryptBufferPromise;
const __webpack_exports__secp256k1PrivateKeyVerify = __webpack_exports__.secp256k1PrivateKeyVerify;
const __webpack_exports__secp256k1PrivateKeyVerifyBuffer = __webpack_exports__.secp256k1PrivateKeyVerifyBuffer;
const __webpack_exports__secp256k1PublicKeyCreate = __webpack_exports__.secp256k1PublicKeyCreate;
const __webpack_exports__secp256k1PublicKeyCreateBuffer = __webpack_exports__.secp256k1PublicKeyCreateBuffer;
const __webpack_exports__secp256k1PublicKeyVerify = __webpack_exports__.secp256k1PublicKeyVerify;
const __webpack_exports__secp256k1PublicKeyVerifyBuffer = __webpack_exports__.secp256k1PublicKeyVerifyBuffer;
const __webpack_exports__secp256k1RecoverPublicKey = __webpack_exports__.secp256k1RecoverPublicKey;
const __webpack_exports__secp256k1RecoverPublicKeyBuffer = __webpack_exports__.secp256k1RecoverPublicKeyBuffer;
const __webpack_exports__secp256k1SharedSecret = __webpack_exports__.secp256k1SharedSecret;
const __webpack_exports__secp256k1SharedSecretBuffer = __webpack_exports__.secp256k1SharedSecretBuffer;
const __webpack_exports__secp256k1SignRecoverable = __webpack_exports__.secp256k1SignRecoverable;
const __webpack_exports__secp256k1SignRecoverableBuffer = __webpack_exports__.secp256k1SignRecoverableBuffer;
const __webpack_exports__setClipboardContent = __webpack_exports__.setClipboardContent;
const __webpack_exports__setTimeout = __webpack_exports__.setTimeout;
const __webpack_exports__sha256 = __webpack_exports__.sha256;
const __webpack_exports__sha256Buffer = __webpack_exports__.sha256Buffer;
const __webpack_exports__sign = __webpack_exports__.sign;
const __webpack_exports__signBuffer = __webpack_exports__.signBuffer;
const __webpack_exports__toJSON = __webpack_exports__.toJSON;
const __webpack_exports__verify = __webpack_exports__.verify;
const __webpack_exports__verifyBuffer = __webpack_exports__.verifyBuffer;
const __webpack_exports__vfileDeleteAsync = __webpack_exports__.vfileDeleteAsync;
const __webpack_exports__vfileDeletePromise = __webpack_exports__.vfileDeletePromise;
const __webpack_exports__vfileExistsAsync = __webpack_exports__.vfileExistsAsync;
const __webpack_exports__vfileExistsPromise = __webpack_exports__.vfileExistsPromise;
const __webpack_exports__vfileListAllAsync = __webpack_exports__.vfileListAllAsync;
const __webpack_exports__vfileListAllPromise = __webpack_exports__.vfileListAllPromise;
const __webpack_exports__vfileReadAsync = __webpack_exports__.vfileReadAsync;
const __webpack_exports__vfileReadPromise = __webpack_exports__.vfileReadPromise;
const __webpack_exports__vfileWriteAsync = __webpack_exports__.vfileWriteAsync;
const __webpack_exports__vfileWritePromise = __webpack_exports__.vfileWritePromise;
const __webpack_exports__websocketConsumeEvent = __webpack_exports__.websocketConsumeEvent;
const __webpack_exports__websocketEventBinaryLength = __webpack_exports__.websocketEventBinaryLength;
const __webpack_exports__websocketEventText = __webpack_exports__.websocketEventText;
const __webpack_exports__websocketEventType = __webpack_exports__.websocketEventType;
const __webpack_exports__websocketInitEventQueue = __webpack_exports__.websocketInitEventQueue;
const __webpack_exports__websocketOpenPromise = __webpack_exports__.websocketOpenPromise;
const __webpack_exports__websocketReadBinaryEvent = __webpack_exports__.websocketReadBinaryEvent;
const __webpack_exports__xchacha20poly1305 = __webpack_exports__.xchacha20poly1305;
const __webpack_exports__xchacha20poly1305Buffer = __webpack_exports__.xchacha20poly1305Buffer;
export { __webpack_exports___bw as _bw, __webpack_exports__aes256cbc as aes256cbc, __webpack_exports__aes256cbcBuffer as aes256cbcBuffer, __webpack_exports__base64decode as base64decode, __webpack_exports__base64decodeBuffer as base64decodeBuffer, __webpack_exports__base64encode as base64encode, __webpack_exports__base64encodeBuffer as base64encodeBuffer, __webpack_exports__callFunction as callFunction, __webpack_exports__callFunctionPromise as callFunctionPromise, __webpack_exports__canCallFunction as canCallFunction, __webpack_exports__canCallFunctionPromise as canCallFunctionPromise, __webpack_exports__chacha20 as chacha20, __webpack_exports__chacha20Buffer as chacha20Buffer, __webpack_exports__copyHttpResponseBody as copyHttpResponseBody, __webpack_exports__delayPromise as delayPromise, __webpack_exports__eventQueueDispose as eventQueueDispose, __webpack_exports__eventQueueWaitPromise as eventQueueWaitPromise, __webpack_exports__fetchAsync as fetchAsync, __webpack_exports__fetchBufferAsync as fetchBufferAsync, __webpack_exports__fetchBufferPromise as fetchBufferPromise, __webpack_exports__fetchPromise as fetchPromise, __webpack_exports__fetchStreamAsync as fetchStreamAsync, __webpack_exports__fetchStreamPromise as fetchStreamPromise, __webpack_exports__fromJSON as fromJSON, __webpack_exports__genPubKey as genPubKey, __webpack_exports__genPubKeyBuffer as genPubKeyBuffer, __webpack_exports__generatePrivateKey as generatePrivateKey, __webpack_exports__generatePrivateKeyBuffer as generatePrivateKeyBuffer, __webpack_exports__getBundledResource as getBundledResource, __webpack_exports__getClipboardContentAsync as getClipboardContentAsync, __webpack_exports__getClipboardContentPromise as getClipboardContentPromise, __webpack_exports__getPlatformName as getPlatformName, __webpack_exports__getPromise as getPromise, __webpack_exports__getRuntimeName as getRuntimeName, __webpack_exports__hasBundledResource as hasBundledResource, __webpack_exports__hkdfExpandBuffer as hkdfExpandBuffer, __webpack_exports__hkdfExtractBuffer as hkdfExtractBuffer, __webpack_exports__hkdf_expand as hkdf_expand, __webpack_exports__hkdf_extract as hkdf_extract, __webpack_exports__hmac as hmac, __webpack_exports__hmacBuffer as hmacBuffer, __webpack_exports__httpResponseBodyLength as httpResponseBodyLength, __webpack_exports__newPromise as newPromise, __webpack_exports__nfkc as nfkc, __webpack_exports__openURL as openURL, __webpack_exports__panic as panic, __webpack_exports__randomBytes as randomBytes, __webpack_exports__randomBytesBuffer as randomBytesBuffer, __webpack_exports__rejectPromise as rejectPromise, __webpack_exports__resolvePromise as resolvePromise, __webpack_exports__rtcAddIceCandidateAsync as rtcAddIceCandidateAsync, __webpack_exports__rtcAddIceCandidatePromise as rtcAddIceCandidatePromise, __webpack_exports__rtcCreateAnswerAsync as rtcCreateAnswerAsync, __webpack_exports__rtcCreateAnswerPromise as rtcCreateAnswerPromise, __webpack_exports__rtcCreateDataChannel as rtcCreateDataChannel, __webpack_exports__rtcCreateIceCandidate as rtcCreateIceCandidate, __webpack_exports__rtcCreateOfferAsync as rtcCreateOfferAsync, __webpack_exports__rtcCreateOfferPromise as rtcCreateOfferPromise, __webpack_exports__rtcCreatePeerConnection as rtcCreatePeerConnection, __webpack_exports__rtcDataChannelConsumeEvent as rtcDataChannelConsumeEvent, __webpack_exports__rtcDataChannelEventBinaryLength as rtcDataChannelEventBinaryLength, __webpack_exports__rtcDataChannelEventError as rtcDataChannelEventError, __webpack_exports__rtcDataChannelEventType as rtcDataChannelEventType, __webpack_exports__rtcDataChannelGetAvailableAmount as rtcDataChannelGetAvailableAmount, __webpack_exports__rtcDataChannelGetBufferedAmount as rtcDataChannelGetBufferedAmount, __webpack_exports__rtcDataChannelGetMaxPacketLifeTime as rtcDataChannelGetMaxPacketLifeTime, __webpack_exports__rtcDataChannelGetMaxRetransmits as rtcDataChannelGetMaxRetransmits, __webpack_exports__rtcDataChannelGetProtocol as rtcDataChannelGetProtocol, __webpack_exports__rtcDataChannelIsOrdered as rtcDataChannelIsOrdered, __webpack_exports__rtcDataChannelIsReliable as rtcDataChannelIsReliable, __webpack_exports__rtcDataChannelSetBufferedAmountLowThreshold as rtcDataChannelSetBufferedAmountLowThreshold, __webpack_exports__rtcGetMaxMessageSize as rtcGetMaxMessageSize, __webpack_exports__rtcInitDataChannelEventQueue as rtcInitDataChannelEventQueue, __webpack_exports__rtcInitPeerEventQueue as rtcInitPeerEventQueue, __webpack_exports__rtcPeerConsumeEvent as rtcPeerConsumeEvent, __webpack_exports__rtcPeerEventCandidate as rtcPeerEventCandidate, __webpack_exports__rtcPeerEventChannel as rtcPeerEventChannel, __webpack_exports__rtcPeerEventState as rtcPeerEventState, __webpack_exports__rtcPeerEventType as rtcPeerEventType, __webpack_exports__rtcReadDataChannelBinaryEvent as rtcReadDataChannelBinaryEvent, __webpack_exports__rtcSetLocalDescriptionAsync as rtcSetLocalDescriptionAsync, __webpack_exports__rtcSetLocalDescriptionPromise as rtcSetLocalDescriptionPromise, __webpack_exports__rtcSetOnMessageHandler as rtcSetOnMessageHandler, __webpack_exports__rtcSetRemoteDescriptionAsync as rtcSetRemoteDescriptionAsync, __webpack_exports__rtcSetRemoteDescriptionPromise as rtcSetRemoteDescriptionPromise, __webpack_exports__scryptAsync as scryptAsync, __webpack_exports__scryptBufferPromise as scryptBufferPromise, __webpack_exports__secp256k1PrivateKeyVerify as secp256k1PrivateKeyVerify, __webpack_exports__secp256k1PrivateKeyVerifyBuffer as secp256k1PrivateKeyVerifyBuffer, __webpack_exports__secp256k1PublicKeyCreate as secp256k1PublicKeyCreate, __webpack_exports__secp256k1PublicKeyCreateBuffer as secp256k1PublicKeyCreateBuffer, __webpack_exports__secp256k1PublicKeyVerify as secp256k1PublicKeyVerify, __webpack_exports__secp256k1PublicKeyVerifyBuffer as secp256k1PublicKeyVerifyBuffer, __webpack_exports__secp256k1RecoverPublicKey as secp256k1RecoverPublicKey, __webpack_exports__secp256k1RecoverPublicKeyBuffer as secp256k1RecoverPublicKeyBuffer, __webpack_exports__secp256k1SharedSecret as secp256k1SharedSecret, __webpack_exports__secp256k1SharedSecretBuffer as secp256k1SharedSecretBuffer, __webpack_exports__secp256k1SignRecoverable as secp256k1SignRecoverable, __webpack_exports__secp256k1SignRecoverableBuffer as secp256k1SignRecoverableBuffer, __webpack_exports__setClipboardContent as setClipboardContent, __webpack_exports__setTimeout as setTimeout, __webpack_exports__sha256 as sha256, __webpack_exports__sha256Buffer as sha256Buffer, __webpack_exports__sign as sign, __webpack_exports__signBuffer as signBuffer, __webpack_exports__toJSON as toJSON, __webpack_exports__verify as verify, __webpack_exports__verifyBuffer as verifyBuffer, __webpack_exports__vfileDeleteAsync as vfileDeleteAsync, __webpack_exports__vfileDeletePromise as vfileDeletePromise, __webpack_exports__vfileExistsAsync as vfileExistsAsync, __webpack_exports__vfileExistsPromise as vfileExistsPromise, __webpack_exports__vfileListAllAsync as vfileListAllAsync, __webpack_exports__vfileListAllPromise as vfileListAllPromise, __webpack_exports__vfileReadAsync as vfileReadAsync, __webpack_exports__vfileReadPromise as vfileReadPromise, __webpack_exports__vfileWriteAsync as vfileWriteAsync, __webpack_exports__vfileWritePromise as vfileWritePromise, __webpack_exports__websocketConsumeEvent as websocketConsumeEvent, __webpack_exports__websocketEventBinaryLength as websocketEventBinaryLength, __webpack_exports__websocketEventText as websocketEventText, __webpack_exports__websocketEventType as websocketEventType, __webpack_exports__websocketInitEventQueue as websocketInitEventQueue, __webpack_exports__websocketOpenPromise as websocketOpenPromise, __webpack_exports__websocketReadBinaryEvent as websocketReadBinaryEvent, __webpack_exports__xchacha20poly1305 as xchacha20poly1305, __webpack_exports__xchacha20poly1305Buffer as xchacha20poly1305Buffer };
