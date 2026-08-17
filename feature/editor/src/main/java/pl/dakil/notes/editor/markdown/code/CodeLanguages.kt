package pl.dakil.notes.editor.markdown.code

/**
 * The language table.
 *
 * Keyword lists are derived from the highlight.js language definitions (BSD-3-Clause), flattened
 * into one space-separated string per language. A `when` over the id rather than a map built at
 * class-load: the branch that matches is the only string constant the runtime ever touches, so a
 * note containing one Python block never pays for the other fifty-five languages.
 *
 * Generated. Comment and quote conventions are hand-maintained alongside.
 */
internal object CodeLanguages {

    /** Spoken aliases people actually write after the opening fence. */
    private fun canonical(name: String): String = when (val id = name.trim().lowercase()) {
        "js", "node", "mjs", "cjs" -> "javascript"
        "ts", "tsx", "jsx" -> "typescript"
        "py", "python3" -> "python"
        "rb" -> "ruby"
        "kt", "kts" -> "kotlin"
        "sh", "zsh", "shell", "console" -> "bash"
        "c++", "cc", "hpp", "cxx" -> "cpp"
        "h" -> "c"
        "cs", "c#" -> "csharp"
        "fs", "f#" -> "fsharp"
        "vb" -> "vbnet"
        "objc", "obj-c" -> "objectivec"
        "golang" -> "go"
        "rs" -> "rust"
        "yml" -> "yaml"
        "toml" -> "ini"
        "htm", "html", "svg", "xhtml" -> "xml"
        "tex" -> "latex"
        "ps1", "pwsh" -> "powershell"
        "docker" -> "dockerfile"
        "make", "mk" -> "makefile"
        "md" -> "markdown"
        "gradle" -> "groovy"
        "proto" -> "protobuf"
        "postgres", "postgresql", "mysql", "sqlite" -> "sql"
        "m" -> "matlab"
        "pl" -> "perl"
        "ex", "exs" -> "elixir"
        "erl" -> "erlang"
        "hs" -> "haskell"
        "ml" -> "ocaml"
        "f90", "f95" -> "fortran"
        "jl" -> "julia"
        "cmakelists" -> "cmake"
        "scm" -> "scheme"
        "el", "emacs" -> "lisp"
        "clj", "cljs", "edn" -> "clojure"
        "sv", "systemverilog" -> "verilog"
        else -> id
    }

    private val cache = HashMap<String, LanguageSpec?>()

    /** The spec for [name], or null when this build has no keywords for it. */
    fun of(name: String): LanguageSpec? {
        if (name.isBlank()) return null
        val id = canonical(name)
        // A note tends to use the same two or three languages over and over, so the miss is paid
        // once per language per session rather than once per keystroke.
        return cache.getOrPut(id) { build(id) }
    }

    private fun build(id: String): LanguageSpec? = when (id) {
        "bash" -> LanguageSpec(
            keywords = "alias autoload bg bind bindkey break builtin bye caller cap case cd chdir clone command comparguments compcall compctl compdescribe compfiles compgroups compquote comptags comptry compvalues continue coproc declare dirs disable disown do done echo echotc echoti elif else emulate enable esac eval exec exit export false fc fg fi float for function functions getcap getln getopts hash help history if in integer jobs kill let limit local log logout mapfile noglob popd print printf pushd pushln pwd read readarray readonly rehash return sched select set setcap setopt shift shopt source stat sudo suspend test then time times trap true ttyctl type typeset ulimit umask unalias unfunction unhash unlimit unset unsetopt until vared wait whence where which while zcompile zformat zftp zle zmodload zparseopts zprof zpty zregexparse zsocket zstyle ztcp",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "c" -> LanguageSpec(
            keywords = "NULL _Alignas _Alignof _Atomic _BitInt _Bool _Complex _Decimal128 _Decimal128x _Decimal32 _Decimal64 _Decimal64x _Decimal96 _Float128 _Float128x _Float16 _Float32 _Float32x _Float64 _Float64x _Generic _Imaginary _Noreturn _Pragma _Static_assert _Thread_local abort abs acos alignas alignof asin asm atan atan2 auto bool break calloc case ceil char complex const constexpr continue cos cosh default define do double elif elifdef elifndef else endif enum error exit exp extern fabs false float floor fmod for fortran fprintf fputs free frexp fscanf goto if ifdef ifndef imaginary include inline int isalnum isalpha iscntrl isdigit isgraph islower isprint ispunct isspace isupper isxdigit labs ldexp line log log10 long malloc memchr memcmp memcpy memset modf noreturn pow pragma printf putchar puts realloc register restrict return scanf short signed sin sinh sizeof snprintf sprintf sqrt sscanf static static_assert stderr stdin stdout strcat strchr strcmp strcpy strcspn strlen strncat strncmp strncpy strpbrk strrchr strspn strstr struct switch tan tanh thread_local tolower toupper true typedef typeof typeof_unqual undef union unsigned vfprintf void volatile vprintf vsprintf warning while",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "clojure" -> LanguageSpec(
            keywords = "",
            lineComment = ";",
            blockOpen = null,
            blockClose = null,
            quotes = "\"",
            tripleQuoted = false,
        )
        "cmake" -> LanguageSpec(
            keywords = "add_compile_options add_custom_command add_custom_target add_definitions add_dependencies add_executable add_library add_link_options add_subdirectory add_test and aux_source_directory block break build_command build_name cmake_policy command configure_file continue ctest_build ctest_configure ctest_coverage ctest_memcheck ctest_run_script ctest_sleep ctest_start ctest_submit ctest_test ctest_update ctest_upload define_property defined else elseif enable_language enable_testing endblock endforeach endfunction endmacro endwhile equal exec_program execute_process exists export false file find_file find_library find_package find_path find_program fltk_wrap_ui foreach function get_property get_target_property get_test_property greater greater_equal if in_list include include_directories include_guard install install_files install_programs install_targets is_absolute is_directory is_newer_than is_symlink less less_equal link_directories link_libraries list load_cache load_command macro make_directory mark_as_advanced matches math message not off on option or policy project qt5_use_modules qt5_use_package qt5_wrap_cpp qt_wrap_cpp qt_wrap_ui remove remove_definitions return separate_arguments set set_property set_tests_properties site_name source_group strequal strgreater strgreater_equal string strless strless_equal subdir_depends subdirs target target_link_options target_sources test true try_compile try_run unset use_mangled_mesa utility_source variable_requires variable_watch version_equal version_greater version_less version_less_equal while write_file",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"",
            tripleQuoted = false,
        )
        "cpp" -> LanguageSpec(
            keywords = "NULL _Alignas _Alignof _Atomic _BitInt _Bool _Complex _Decimal128 _Decimal128x _Decimal32 _Decimal64 _Decimal64x _Decimal96 _Float128 _Float128x _Float16 _Float32 _Float32x _Float64 _Float64x _Generic _Imaginary _Noreturn _Pragma _Static_assert _Thread_local abort abs acos alignas alignof and and_eq any apply as_const asin asm atan atan2 atomic_cancel atomic_commit atomic_noexcept auto auto_ptr barrier binary_semaphore bitand bitor bitset bool break calloc case catch ceil cerr char char16_t char32_t char8_t cin class clog co_await co_return co_yield compl complex concept condition_variable const const_cast consteval constexpr constinit continue contract_assert cos cosh counting_semaphore cout decltype declval default define delete deque do double dynamic_cast elif elifdef elifndef else embed endif endl enum error exchange exit exp explicit export extern fabs false false_type final flat_map flat_set float floor fmod for fortran forward fprintf fputs free frexp friend fscanf future goto if ifdef ifndef imaginary import include initializer_list inline int invoke isalnum isalpha iscntrl isdigit isgraph islower isprint ispunct isspace istringstream isupper isxdigit jthread labs latch launder ldexp line lock_guard log log10 long make_pair make_shared make_tuple make_unique malloc memchr memcmp memcpy memset modf module move multimap multiset mutable mutex namespace new noexcept noreturn not not_eq nullopt nullptr operator optional or or_eq ostringstream override packaged_task pair post pow pragma pre printf priority_queue private promise protected public putchar puts queue realloc recursive_mutex reflexpr register reinterpret_cast requires restrict return scanf scoped_lock set shared_future shared_lock shared_mutex shared_ptr shared_timed_mutex short signed sin sinh sizeof snprintf sprintf sqrt sscanf stack static static_assert static_cast std stderr stdin stdout strcat strchr strcmp strcpy strcspn string_view stringstream strlen strncat strncmp strncpy strpbrk strrchr strspn strstr struct swap switch synchronized tan tanh template terminate this thread thread_local throw timed_mutex to_underlying tolower toupper transaction_safe true true_type try tuple typedef typeid typename typeof typeof_unqual undef union unique_lock unique_ptr unordered_map unordered_multimap unordered_multiset unordered_set unsigned using variant vector vfprintf virtual visit void volatile vprintf vsprintf warning wchar_t weak_ptr while wstring wstring_view xor xor_eq",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "crystal" -> LanguageSpec(
            keywords = "__DIR__ __END_LINE__ __FILE__ __LINE__ abstract alias annotation as asm begin break case class def do else elsif end ensure enum extend false for fun if include instance_sizeof lib macro module next nil of out pointerof private protected require rescue return select self sizeof struct super then true type typeof uninitialized union unless until verbatim when while with yield",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "csharp" -> LanguageSpec(
            keywords = "abstract add alias and args as ascending async await base bool break by byte case catch char checksum class const continue decimal default define delegate descending do double dynamic elif else endif endregion enum equals error event explicit extern false file finally fixed float for foreach from get global goto group if implicit in init int interface internal into is join let line lock long nameof namespace new nint not notnull nuint null object on operator or orderby out override params partial pragma private protected public readonly record ref region remove required return sbyte scoped sealed select set short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked undef unmanaged unsafe ushort using value var virtual void volatile warning when where while with yield",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "css" -> LanguageSpec(
            keywords = "all animation appearance background border bottom clear clip color columns contain container content cue cursor cx cy direction display fill filter flex float flow font from gap grid height hover hyphens icon inset isolation kerning left margin marker marks mask monochrome none normal offset opacity order orientation orphans outline overflow overlay padding page pause perspective pointer position quotes resize resolution rest right rotate scale scan scripting speak src stroke to top transform transition translate update url visibility widows width zoom",
            lineComment = null,
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "d" -> LanguageSpec(
            keywords = "__DATE__ __EOF__ __FILE__ __LINE__ __TIMESTAMP__ __TIME__ __VENDOR__ __VERSION__ __gshared __thread __traits abstract alias align asm assert auto body bool break byte case cast catch cdouble cent cfloat char class const creal dchar debug default delegate delete deprecated do double dstring else enum export extern false final finally float for foreach foreach_reverse function goto idouble if ifloat immutable import in inout int interface invariant ireal is lazy long macro mixin module new nothrow null out override package pragma private protected public pure real ref return scope shared short static string struct super switch synchronized template this throw true try typedef typeid typeof ubyte ucent uint ulong union unittest ushort version void volatile wchar while with wstring",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'`",
            tripleQuoted = false,
        )
        "dart" -> LanguageSpec(
            keywords = "Comparable DateTime Duration Element ElementList Function Iterable Iterator List Map Match Object Pattern RegExp Set Stopwatch String StringBuffer StringSink Symbol Type Uri abstract as assert async await base bool break case catch class const continue covariant default deferred do double dynamic else enum export extends extension external factory false final finally for get hide if implements import in int interface is late library mixin new null num on operator part required rethrow return sealed set show static super switch sync this throw true try typedef var void when while with yield",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = true,
        )
        "dockerfile" -> LanguageSpec(
            keywords = "arg env expose from maintainer onbuild stopsignal user",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "elixir" -> LanguageSpec(
            keywords = "after alias and case catch cond defguard defguardp defstruct do else end false fn for if import in nil not or quote raise receive require reraise rescue true try unless unquote unquote_splicing use when with",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = true,
        )
        "elm" -> LanguageSpec(
            keywords = "alias as case command effect else exposing if import in infix infixl infixr let module of port subscription then type where",
            lineComment = "--",
            blockOpen = "{-",
            blockClose = "-}",
            quotes = "\"",
            tripleQuoted = false,
        )
        "erlang" -> LanguageSpec(
            keywords = "after and andalso band begin bnot bor bsl bxor bzr case catch cond div else end false fun if let maybe of orelse query receive rem true try when xor",
            lineComment = "%",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "fortran" -> LanguageSpec(
            keywords = "C_intptr_t abs abstract access achar acos acosh action adjustl adjustr advance aimag aint algama all allocatable allocate allocated alog alog10 amax0 amax1 amin0 amin1 amod anint any asin asinh assign associate associated atan atan2 atanh atomic_define atomic_ref bessel_j0 bessel_j1 bessel_jn bessel_y0 bessel_y1 bessel_yn bge bgt bind bit_size blank ble block blt btest c_alert c_associated c_backspace c_bool c_carriage_return c_char c_double c_double_complex c_f_pointer c_f_procpointer c_float c_float_complex c_form_feed c_funloc c_funptr c_horizontal_tab c_int c_int16_t c_int32_t c_int64_t c_int8_t c_int_fast16_t c_int_fast32_t c_int_fast64_t c_int_fast8_t c_int_least16_t c_int_least32_t c_int_least64_t c_int_least8_t c_intmax_t c_loc c_long c_long_double c_long_long c_new_line c_null_char c_null_funptr c_null_ptr c_ptr c_short c_signed_char c_size_t c_vertical_tab cabs call case ccos cdabs cdcos cdexp cdlog cdsin cdsqrt ceiling cexp change char character class clog cmplx co_broadcast co_max co_min co_reduce co_sum codimension common complex concurrent conjg contains contiguous continue cos cosh count cpu_time cqabs cqcos cqexp cqlog cqsin cqsqrt cshift csin csqrt cycle dabs dacos dasin data datan datan2 date_and_time dble dcmplx dconjg dcos dcosh ddim deallocate decimal default deferred delim derf derfc dexp dfloat dgamma digits dim dimag dimension dint direct dlgama dlog dlog10 dmax1 dmin1 dmod dnint do dot_product double dprod dshiftl dshiftr dsign dsin dsinh dsqrt dtan dtanh elemental else elsewhere end endassociate endblock enddo endforall endif endinterface endmodule endselect endtype entry enum enumerator eor eoshift epsilon equivalence erf erfc erfc_scaled error_unit execute_command_line exist exit exp exponent extends extends_type_of external file file_storage_size final findloc float floor flush fmt forall form format formatted fraction function gamma generic get_command get_command_argument goto huge hypot iabs iachar iall iand iany ibclr ibits ibset ichar idim idint idnint ieee_arithmetic ieor if ifix image_index implicit import impure in include index input_unit int integer intent interface intrinsic iomsg ior iostat iostat_end iostat_eor iparity iqint is_iostat_end is_iostat_eor ishft ishftc isign iso_c_binding iso_fortran_env kind lbound lcobound leadz len_trim lge lgt lle llt local log log10 log_gamma logical maskl maskr matmul max max0 max1 maxexponent maxloc maxval merge merge_bits min min0 min1 minexponent minloc minval mod module modulo move_alloc mvbits name named namelist nearest new_line newunit nextrec nint nml non_intrinsic non_overridable none nopass norm2 nullify num_images number numeric_storage_size only opened optional out output_unit pack pad parameter parity pass pause pointer popcnt poppar position precision present print private procedure product protected public pure qabs qacos qasin qatan qatan2 qcmplx qconjg qcos qcosh qdim qerf qerfc qexp qgamma qimag qlgama qlog qlog10 qmax1 qmin1 qmod qnint qsign qsin qsinh qsqrt qtan qtanh radix random_number random_seed range readwrite real rec recl recursive repeat reshape return round rrspacing same_type_as save scale scan select selected_char_kind selected_int_kind selected_real_kind sequence sequential set_exponent shape shared shifta shiftl shiftr sign sin sinh size sngl spacing spread sqrt status stop storage_size subroutine sum sync synchronous system_clock tan tanh target team then this_image tiny trailz transfer transpose trim type ubound ucobound unformatted unit unpack use value verify volatile wait where while write",
            lineComment = "!",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "fsharp" -> LanguageSpec(
            keywords = "Error None Ok Result Some abstract and array as assert base begin bigint bool box byref byte class decimal default delegate dict do done double downcast downto elif else end endif enum eprintf eprintfn exception exn extern failwith failwithf false finally fixed float float32 for fprintf fprintfn fst fun function get global help id if ignore in infinity infinityf inherit inline inref int int16 int32 int64 int8 interface internal invalidArg invalidOp lazy let light line list load lock match member module mutable nameof namespace nan nanf nativeint nativeptr new not nowarn null nullArg obj of open option or outref override printf printfn private public quit raise readOnlyDict rec ref reraise return sbyte seq set single sizeof snd sprintf static string struct then time to true try tryUnbox type typedefof typeof uint uint16 uint32 uint64 uint8 unativeint unbox unit upcast use using val void voidptr voption when while with yield",
            lineComment = "//",
            blockOpen = "(*",
            blockClose = "*)",
            quotes = "\"'",
            tripleQuoted = true,
        )
        "go" -> LanguageSpec(
            keywords = "append bool byte cap close complex complex128 complex64 copy delete error false float32 float64 imag int int16 int32 int64 int8 iota len make new nil panic print println real recover rune string true uint uint16 uint32 uint64 uint8 uintptr",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'`",
            tripleQuoted = false,
        )
        "graphql" -> LanguageSpec(
            keywords = "directive enum false fragment input interface mutation null on query scalar schema subscription true type union",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"",
            tripleQuoted = true,
        )
        "groovy" -> LanguageSpec(
            keywords = "abstract as assert boolean break byte case catch char class continue def default double else enum extends false final finally float for if implements import in instanceof int interface long new null package private protected public return short static super switch synchronized this throw throws trait transient true try var void volatile while",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = true,
        )
        "haskell" -> LanguageSpec(
            keywords = "as case ccall class cplusplus data default deriving do dotnet else export family forall foreign hiding if import infix infixl infixr instance jvm let mdo module newtype of proc qualified rec safe stdcall then type unsafe where",
            lineComment = "--",
            blockOpen = "{-",
            blockClose = "-}",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "ini" -> LanguageSpec(
            keywords = "",
            lineComment = ";",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "java" -> LanguageSpec(
            keywords = "abstract assert boolean break byte case catch char const continue default do double else enum exports false final finally float for goto if import instanceof int long module native null package permits private protected public requires sealed short static strictfp super switch synchronized this throws transient true try var void volatile when while yield",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = true,
        )
        "javascript" -> LanguageSpec(
            keywords = "Array ArrayBuffer AsyncFunction Atomics BigInt BigInt64Array BigUint64Array Boolean DataView Date Error EvalError Float32Array Float64Array Function Generator GeneratorFunction Infinity Int16Array Int32Array Int8Array InternalError Intl JSON Map Math NaN Number Object Promise Proxy RangeError ReferenceError Reflect RegExp Set SharedArrayBuffer String Symbol SyntaxError TypeError URIError Uint16Array Uint32Array Uint8Array Uint8ClampedArray WeakMap WeakSet WebAssembly arguments as async await break case catch class clearInterval clearTimeout console const continue debugger decodeURI decodeURIComponent default delete do document else encodeURI encodeURIComponent escape eval export exports extends false finally for from function get global if implementation import in instanceof isFinite isNaN let localStorage module new null of parseFloat parseInt prototype recommended require return self sessionStorage set setInterval setTimeout static super switch this throw true try typeof undefined unescape using var void while window with yield",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'`",
            tripleQuoted = false,
        )
        "json" -> LanguageSpec(
            keywords = "false null true",
            lineComment = null,
            blockOpen = null,
            blockClose = null,
            quotes = "\"",
            tripleQuoted = false,
        )
        "julia" -> LanguageSpec(
            keywords = "ARGS AbstractArray AbstractChannel AbstractChar AbstractDict AbstractDisplay AbstractFloat AbstractIrrational AbstractMatrix AbstractRange AbstractSet AbstractString AbstractUnitRange AbstractVecOrMat AbstractVector Any ArgumentError Array AssertionError BigFloat BigInt BitArray BitMatrix BitSet BitVector Bool BoundsError C_NULL CapturedException CartesianIndex CartesianIndices Cchar Cdouble Cfloat Channel Char Cint Cintmax_t Clong Clonglong Cmd Colon Complex ComplexF16 ComplexF32 ComplexF64 CompositeException Condition Cptrdiff_t Cshort Csize_t Cssize_t Cstring Cuchar Cuint Cuintmax_t Culong Culonglong Cushort Cvoid Cwchar_t Cwstring DEPOT_PATH DataType DenseArray DenseMatrix DenseVecOrMat DenseVector Dict DimensionMismatch Dims DivideError DomainError ENDIAN_BOM ENV EOFError Enum ErrorException Exception ExponentialBackOff Expr Float16 Float32 Float64 Function GlobalRef HTML IO IOBuffer IOContext IOStream IdDict IndexCartesian IndexLinear IndexStyle InexactError Inf Inf16 Inf32 Inf64 InitError InsertionSort Int Int128 Int16 Int32 Int64 Int8 Integer InterruptException Irrational KeyError LOAD_PATH LinRange LineNumberNode LinearIndices LoadError MIME Matrix MergeSort Method MethodError Missing MissingException Module NTuple NaN NaN16 NaN32 NaN64 NamedTuple Nothing Number OrdinalRange OutOfMemoryError OverflowError PROGRAM_FILE Pair PartialQuickSort PermutedDimsArray Pipe Ptr QuickSort QuoteNode Rational RawFD ReadOnlyMemoryError Real ReentrantLock Ref Regex RegexMatch RoundDown RoundFromZero RoundNearest RoundNearestTiesAway RoundNearestTiesUp RoundToZero RoundUp RoundingMode SegmentationFault Set Signed Some StackOverflowError StepRange StepRangeLen StridedArray StridedMatrix StridedVecOrMat StridedVector String StringIndexError SubArray SubString SubstitutionString Symbol SystemError Task TaskFailedException Text TextDisplay Timer Tuple Type TypeError TypeVar UInt UInt128 UInt16 UInt32 UInt64 UInt8 UndefInitializer UndefKeywordError UndefRefError UndefVarError Union UnionAll UnitRange Unsigned VERSION Val Vararg VecElement VecOrMat Vector VersionNumber WeakKeyDict WeakRef baremodule begin break catch ccall const continue devnull do else elseif end export false finally for function global if im import in isa let local macro missing module nothing pi quote return stderr stdin stdout true try undef using where while",
            lineComment = "#",
            blockOpen = "#=",
            blockClose = "=#",
            quotes = "\"'",
            tripleQuoted = true,
        )
        "kotlin" -> LanguageSpec(
            keywords = "Boolean Byte Char Double Float Int Long Nothing Short Unit Void abstract actual annotation as by catch class companion const constructor crossinline data do dynamic else enum expect external false final finally for fun get if import in infix init inline interface internal is lateinit noinline null object open operator out override package private protected public reified sealed set super suspend tailrec throw trait true try typealias val var when where while",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'`",
            tripleQuoted = true,
        )
        "latex" -> LanguageSpec(
            keywords = "",
            lineComment = "%",
            blockOpen = null,
            blockClose = null,
            quotes = "\"",
            tripleQuoted = false,
        )
        "less" -> LanguageSpec(
            keywords = "all animation appearance background border bottom clear clip color columns contain container content cue cursor cx cy direction display fill filter flex float flow font from gap grid height hover hyphens icon inset isolation kerning left margin marker marks mask monochrome none normal offset opacity order orientation orphans outline overflow overlay padding page pause perspective pointer position quotes resize resolution rest right rotate scale scan scripting speak src stroke to top transform transition translate update url visibility widows width zoom",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "lisp" -> LanguageSpec(
            keywords = "quote",
            lineComment = ";",
            blockOpen = null,
            blockClose = null,
            quotes = "\"",
            tripleQuoted = false,
        )
        "lua" -> LanguageSpec(
            keywords = "_ENV _G _VERSION __add __call __concat __div __eq __gc __index __le __len __lt __metatable __mod __mode __mul __newindex __pow __sub __tostring __unm abs acos and arg asin assert atan atan2 break byte ceil char clock close collectgarbage concat config coroutine cos cosh cpath create date debug deg difftime do dofile dump else elseif end error execute exit exp false find floor flush fmod for foreach foreachi format frexp getenv getfenv gethook getinfo getlocal getmetatable getn getregistry getupvalue gfind global gmatch goto gsub huge if in input insert io ipairs ldexp len lines load loaded loaders loadfile loadlib loadstring local log log10 lower match math max maxn min mod modf module next nil not open or os output package pairs path pcall pi popen pow preload print rad random randomseed rawequal rawget rawset read remove rename rep repeat require resume return reverse running seeall select self setfenv sethook setlocal setlocale setmetatable setn setupvalue sin sinh sort sqrt status stderr stdin stdout string sub table tan tanh then time tmpfile tmpname tonumber tostring traceback true type unpack until upper while wrap write xpcall yield",
            lineComment = "--",
            blockOpen = "--[[",
            blockClose = "]]",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "makefile" -> LanguageSpec(
            keywords = "abspath addprefix addsuffix and basename call define dir else endef endif error eval export file filter findstring firstword flavor foreach if ifdef ifeq ifndef ifneq include join lastword notdir or origin override patsubst private realpath shell sinclude sort strip subst suffix undefine unexport value vpath warning wildcard word wordlist",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "markdown" -> LanguageSpec(
            keywords = "",
            lineComment = null,
            blockOpen = null,
            blockClose = null,
            quotes = "\"",
            tripleQuoted = false,
        )
        "matlab" -> LanguageSpec(
            keywords = "abs accumarray acos acosd acosh acot acotd acoth acsc acscd acsch airy angle ans arguments asec asecd asech asin asind asinh atan atan2 atand atanh besselh besseli besselj besselk bessely beta betainc betaln blkdiag break bsxfun cart2pol cart2sph case cat catch ceil cellfun circshift classdef compan complex conj continue cos cosd cosh cot cotd coth cplxpair cross csc cscd csch diag disp dot ellipj ellipke else elseif end enumeration eps erf erfc erfcx erfinv events exp expint expm1 eye factor factorial figure find fix flipdim fliplr flipud floor for freqspace function gallery gamma gammainc gammaln gcd global hadamard hankel hilb hold hsv2rgb hypot if imag ind2sub inf intersect invhilb ipermute isempty isequal isequalwithequalnans isfinite isinf ismember isnan isprime isreal isscalar isvector lcm legend legendre length linspace log log10 log1p log2 logspace magic max mean meshgrid methods min mod nan nanmax nanmean nanmin nchoosek ndgrid ndims nextpow2 nthroot num2cell numel ones otherwise parfor pascal perms permute persistent pi plot plot3 pol2cart pow2 primes procrustes properties psi rand randn rat rats readtable real reallog realmax realmin realpow realsqrt rem repmat reshape return rgb2hsv rosser rot90 round scatter scatter3 sec secd sech shiftdim sign sin sind sinh size sort sortrows sph2cart spmd sqrt squeeze sub2ind switch table tan tand tanh toeplitz tril triu try type unwrap vander while why wilkinson writetable zeros",
            lineComment = "%",
            blockOpen = "%{",
            blockClose = "%}",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "nginx" -> LanguageSpec(
            keywords = "blocked break crit debug epoll error false info kqueue last location no none notice off on permanent poll redirect rtsig select true upstream warn yes",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "nim" -> LanguageSpec(
            keywords = "addr and any array as asm auto bind block bool break case cast cchar cdouble cfloat char cint clong clongdouble clonglong concept const continue converter cschar cshort csize cstring cstringarray cuchar cuint culong culonglong cushort defer discard distinct div do elif else end enum except export expr false finally float float32 float64 for from func generic guarded if import in include int int16 int32 int64 int8 interface is isnot iterator let macro method mixin mod nil not notin object of openarray or out pointer proc ptr raise range ref result return semistatic seq set shared shl shr static stderr stdin stdout stmt string template true try tuple type uint uint16 uint32 uint64 uint8 using var varargs void when while with without xor yield",
            lineComment = "#",
            blockOpen = "#[",
            blockClose = "]#",
            quotes = "\"'",
            tripleQuoted = true,
        )
        "objectivec" -> LanguageSpec(
            keywords = "BOOL FALSE NO NULL TRUE YES _Bool bool char define dispatch_async dispatch_once dispatch_once_t dispatch_queue_t dispatch_sync double elif else endif error false float id if ifdef ifndef include int line long nil pragma short signed true undef unichar unsigned void warning wchar_t",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "ocaml" -> LanguageSpec(
            keywords = "and array as asr assert begin bool bytes char class constraint do done downto else end exception exn external false float for fun function if in in_channel include inherit initializer int int32 int64 land lazy lazy_t let list lsl lsr lxor match method mod module mutable nativeint new object of open or out_channel parser private rec ref sig string struct then to true try type unit val value virtual when while with",
            lineComment = null,
            blockOpen = "(*",
            blockClose = "*)",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "perl" -> LanguageSpec(
            keywords = "abs accept alarm and atan2 bind binmode bless break caller chdir chmod chomp chop chown chr chroot class close closedir connect continue cos crypt dbmclose dbmopen defined delete die do dump each else elsif endgrent endhostent endnetent endprotoent endpwent endservent eof eval exec exists exit exp fcntl field fileno flock for foreach fork format formline getc getgrent getgrgid getgrnam gethostbyaddr gethostbyname gethostent getlogin getnetbyaddr getnetbyname getnetent getpeername getpgrp getpriority getprotobyname getprotobynumber getprotoent getpwent getpwnam getpwuid getservbyname getservbyport getservent getsockname getsockopt given glob gmtime goto grep gt hex if index int ioctl join keys kill last lc lcfirst length link listen local localtime log lstat lt ma map method mkdir msgctl msgget msgrcv msgsnd my ne next no not oct open opendir or ord our pack package pipe pop pos print printf prototype push qq quotemeta qw qx rand read readdir readline readlink readpipe recv redo ref rename require reset return reverse rewinddir rindex rmdir say scalar seek seekdir select semctl semget semop send setgrent sethostent setnetent setpgrp setpriority setprotoent setpwent setservent setsockopt shift shmctl shmget shmread shmwrite shutdown sin sleep socket socketpair sort splice split sprintf sqrt srand stat state study sub substr symlink syscall sysopen sysread sysseek system syswrite tell telldir tie tied time times tr truncate uc ucfirst umask undef unless unlink unpack unshift untie until use utime values vec wait waitpid wantarray warn when while write xor",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "php" -> LanguageSpec(
            keywords = "AppendIterator ArgumentCountError ArithmeticError ArrayAccess ArrayIterator ArrayObject AssertionError BackedEnum CachingIterator Closure CompileError Countable Directory DirectoryIterator DivisionByZeroError DomainException EmptyIterator Error ErrorException Exception Fiber FilesystemIterator FilterIterator Generator GlobIterator InfiniteIterator Iterator IteratorAggregate IteratorIterator LengthException LimitIterator LogicException MultipleIterator NoRewindIterator OutOfBoundsException OutOfRangeException OuterIterator OverflowException ParentIterator ParseError RangeException RecursiveIterator RegexIterator RuntimeException SeekableIterator Serializable SplDoublyLinkedList SplFileInfo SplFileObject SplFixedArray SplHeap SplMaxHeap SplMinHeap SplObjectStorage SplObserver SplPriorityQueue SplQueue SplStack SplSubject SplTempFileObject Stringable Throwable Traversable TypeError UnderflowException UnhandledMatchError UnitEnum WeakMap WeakReference __halt_compiler array false new null parent php_user_filter self static stdClass true",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "powershell" -> LanguageSpec(
            keywords = "CFS DateTime ac array asnp begin bool break byte cat catch cd char chdir clc clear clhy cli clp cls clv cnsn compare continue copy cp cpi cpp curl cvpa data dbp decimal del diff dir dnsn do double dynamicparam ebp echo else elseif end epal epcsv epsn erase etsn exit exsn fc fhx filter finally fl for foreach ft fw gal gbp gc gcb gci gcm gcs gdr gerr ghy gi gin gjb gl gm gmo gp gps gpv group gsn gsnp gsv gtz gu gv gwmi hashtable hidden history icm iex if ihy ii in int ipal ipcsv ipmo ipsn irm ise iwmi iwr kill long lp ls man md measure mi mount move mp mv nal ndr ni nmo npssc nsn nv ogv oh param parameter popd process ps pushd pwd rbp rcjb rcsn rd rdr ren return ri rjb rm rmdir rmo rni rnp rp rsn rsnp rujb rv rvpa rwmi sajb sal saps sasv sbp sc scb select set shcm si single sl sleep sls sort sp spjb spps spsv start static string stz sujb sv switch swmi tee throw trap trcm try type until void wget where while wjb write xml",
            lineComment = "#",
            blockOpen = "<#",
            blockClose = "#>",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "prolog" -> LanguageSpec(
            keywords = "",
            lineComment = "%",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "protobuf" -> LanguageSpec(
            keywords = "bool bytes double false fixed32 fixed64 float group import int32 int64 oneof option optional package repeated required returns rpc sfixed32 sfixed64 sint32 sint64 string true uint32 uint64",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "python" -> LanguageSpec(
            keywords = "Any Callable Coroutine Dict Ellipsis False Generic List Literal None NotImplemented Optional Sequence Set True Tuple Type Union __debug__ __import__ abs aiter all and anext any as ascii assert async await bin bool break breakpoint bytearray bytes callable case chr class classmethod compile complex continue def del delattr dict dir divmod elif else enumerate eval except exec filter finally float for format from frozendict frozenset getattr global globals hasattr hash help hex id if import in input int is isinstance issubclass iter lambda lazy len list locals map match max memoryview min next nonlocal not object oct open or ord pass pow print property raise range repr return reversed round sentinel set setattr slice sorted staticmethod str sum super try tuple type vars while with yield zip",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = true,
        )
        "r" -> LanguageSpec(
            keywords = "Arg Conj FALSE Im Inf LETTERS Mod NA NA_character_ NA_complex_ NA_integer_ NA_real_ NULL NaN Re TRUE UseMethod abs acos acosh all any anyNA asin asinh atan atanh attr attributes baseenv break browser call ceiling class cos cosh cospi cummax cummin cumprod cumsum digamma dim dimnames else emptyenv exp expression floor for forceAndCall function gamma globalenv in interactive invisible lazyLoadDBfetch length letters lgamma list log max min missing names nargs next nzchar oldClass pi prod quote range rep repeat retracemem return round seq_along seq_len sign signif sin sinh sinpi sqrt standardGeneric substitute sum switch tan tanh tanpi tracemem trigamma trunc unclass untracemem while xtfrm",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "ruby" -> LanguageSpec(
            keywords = "BEGIN END alias and attr_accessor attr_reader attr_writer begin break case class define_method defined do else elsif end ensure false for if in lambda module module_function next nil not or private_constant proc redo require rescue retry return then true undef unless until when while yield",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        "rust" -> LanguageSpec(
            keywords = "AsMut AsRef Box Clone Copy Debug Default DoubleEndedIterator Drop Eq Err ExactSizeIterator Extend Fn FnMut FnOnce From Into IntoIterator Iterator None Ok Option Ord PartialEq PartialOrd Result Self Send Sized SliceConcatExt Some String Sync ToOwned ToString Vec abstract as async await become bool box break char const continue crate do drop dyn else enum extern f128 f16 f32 f64 false final fn for i128 i16 i32 i64 i8 if impl in isize let loop macro match mod move mut override priv pub raw ref return self static str struct super trait true try type typeof u128 u16 u32 u64 u8 union unsafe unsized use usize virtual where while yield",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "scala" -> LanguageSpec(
            keywords = "abstract break case catch class continue def default do else enum export extends false final finally for forSome given if implicit import inline lazy match new null object override package private protected return super then this throw throws trait transparent true try type val var while with yield",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = true,
        )
        "scheme" -> LanguageSpec(
            keywords = "abs acos and angle append apply asin assoc assq assv atan begin caar cadr car case cdddar cddddr cdr ceiling class cond cons cos define delay denominator display do else eval exp expt field floor force gcd if import inherit interface lambda lcm length letrec list load log magnitude map max member memq memv min mixin modulo newline not numerator or override protect provide public quasiquote quote quotient rationalize read remainder rename require reverse round sin sqrt string substring syntax tan truncate unless values vector when write",
            lineComment = ";",
            blockOpen = null,
            blockClose = null,
            quotes = "\"",
            tripleQuoted = false,
        )
        "scss" -> LanguageSpec(
            keywords = "all animation appearance background border bottom clear clip color columns contain container content cue cursor cx cy direction display fill filter flex float flow font from gap grid height hover hyphens icon inset isolation kerning left margin marker marks mask monochrome none normal offset opacity order orientation orphans outline overflow overlay padding page pause perspective pointer position quotes resize resolution rest right rotate scale scan scripting speak src stroke to top transform transition translate update url visibility widows width zoom",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "sql" -> LanguageSpec(
            keywords = "IS NULL QUERY SQL START abs acos add all allocate alter and any are array array_agg as asc asensitive asin asymmetric at atan atomic authorization avg be begin begin_frame begin_partition between bigint binary blob boolean boost both by call called can cardinality cascaded case cast ceil ceiling char char_length character character_length check classifier clauses clob close coalesce collate collation collect column combo combos commit condition connect const constraint contains context convert copy corr correct corresponding cos cosh count covar_pop covar_samp create cross cube cume_dist current current_catalog current_date current_path current_role current_row current_schema current_time current_timestamp current_user cursor cycle date day deallocate dec decfloat decimal declare default define delete dense_rank deref desc describe deterministic disconnect distinct doesn double drop dynamic each element else empty end end_frame end_partition equals escape every except exec execute exist exists exp external extra extract false fetch filter final first first_value float floor for foreign frame_row free from full function functions fusion get global grant group grouping groups have having highlighted hold hour identified identity in indicator initial inner inout insensitive insert int integer intersect intersection interval into is join json_array json_arrayagg json_exists json_object json_objectagg json_query json_table json_table_primitive json_value keyword lag language large last last_value lateral lead leading left like like_regex listagg ln local localtime localtimestamp log log10 look lower making match match_number match_recognize matches max member merge method min minute mod modifier modifies module month multiset national natural nchar nclob new no none normalize not nth_value ntile null nullif numeric object occurrences_regex octet_length of offset old omit on one only open or order out outer over overlaps overlay parameter partition pattern per percent percent_rank percentile_cont percentile_disc period portion position position_regex power precedes precision prepare primary procedure ptf range rank reads real recursive ref references referencing regr_avgx regr_avgy regr_count regr_intercept regr_r2 regr_slope regr_sxx regr_sxy regr_syy release relevance reserved result return returns revoke right rollback rollup row row_number rows running savepoint scope scroll search second seek select sensitive session_user set should show similar sin sinh skip smallint some specific specifictype sql sqlexception sqlstate sqlwarning sqrt start static stddev_pop stddev_samp strange submultiset subset substring substring_regex succeeds sum symmetric system system_time system_user table tablesample tan tanh that then these those time timestamp timezone timezone_hour timezone_minute to trailing translate translate_regex translation treat trigger trim trim_array true truncate turns type uescape union unique unknown unnest update upper user using value value_of values var_pop var_samp varbinary varchar varying versioning very view we when whenever where width_bucket window with within without words worth year",
            lineComment = "--",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "'\"",
            tripleQuoted = false,
        )
        "swift" -> LanguageSpec(
            keywords = "Any GKInspectable IBAction IBDesignable IBInspectable IBOutlet IBSegueAction NSApplicationMain NSCopying NSManaged Protocol Self Sendable Type UIApplicationMain abs actor all any as assert assertionFailure assignment associatedtype associativity async attached autoclosure await block borrowing break case catch class consume consuming continue convenience copy debugPrint default defer deinit didSet discardableResult distributed do dump dynamic dynamicCallable dynamicMemberLookup each else enum escaping extension fallthrough false fatalError fileprivate final for freestanding frozen func get getVaList guard higherThan iOS if import in indirect infix init inlinable inout internal is isolated lazy left let lowerThan macCatalyst macOS macro main max min mutating nil none nonisolated nonmutating nonobjc numericCast objc objcMembers open operator optional override package pointwiseMax pointwiseMin postfix precedencegroup precondition preconditionFailure prefix print private propertyWrapper protocol public readLine repeat repeatElement required resultBuilder rethrows return right self sequence set some static stride struct subscript super swap swift switch testable throw throws transcode true try tvOS typealias unchecked unknown unowned unsafeBitCast unsafeDowncast usableFromInline var watchOS weak where while willSet withExtendedLifetime withUnsafePointer withVaList zip",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"",
            tripleQuoted = true,
        )
        "typescript" -> LanguageSpec(
            keywords = "Array ArrayBuffer AsyncFunction Atomics BigInt BigInt64Array BigUint64Array Boolean DataView Date Error EvalError Float32Array Float64Array Function Generator GeneratorFunction Infinity Int16Array Int32Array Int8Array InternalError Intl JSON Map Math NaN Number Object Promise Proxy RangeError ReferenceError Reflect RegExp Set SharedArrayBuffer String Symbol SyntaxError TypeError URIError Uint16Array Uint32Array Uint8Array Uint8ClampedArray WeakMap WeakSet WebAssembly abstract any arguments as async await bigint boolean break case catch class clearInterval clearTimeout console const continue debugger declare decodeURI decodeURIComponent default delete do document else encodeURI encodeURIComponent enum escape eval export exports extends false finally for from function get global if implementation implements import in instanceof interface isFinite isNaN let localStorage module never new null number object of override parseFloat parseInt private protected prototype public readonly recommended require return satisfies self sessionStorage set setInterval setTimeout static string super switch symbol this throw true try type typeof undefined unescape unknown using var void while window with yield",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"'`",
            tripleQuoted = false,
        )
        "vbnet" -> LanguageSpec(
            keywords = "addhandler addressof aggregate alias and andalso ansi as assembly async auto await binary boolean by byref byte byval call case catch cbool cbyte cchar cdate cdbl cdec char cint class clng cobj compare const csbyte cshort csng cstr cuint culng cushort custom date decimal declare default delegate dim directcast distinct do double each else elseif enable end enum equals erase error event exit explicit externalsource false finally for friend from function get gettype getxmlnamespace global goto group handles if implements imports in inherits integer interface into is isfalse isnot istrue iterator join key let like long loop me mid mod module mustinherit mustoverride mybase myclass nameof namespace narrowing new next not nothing notinheritable notoverridable object of off on operator option optional or order orelse overloads overridable overrides paramarray partial preserve private property protected public raiseevent readonly redim region removehandler resume return sbyte select set shadows shared short single skip static step stop strict string structure sub synclock take text then throw to true try trycast typeof uinteger ulong unicode until ushort using when where while widening with withevents writeonly xor yield",
            lineComment = "'",
            blockOpen = null,
            blockClose = null,
            quotes = "\"",
            tripleQuoted = false,
        )
        "verilog" -> LanguageSpec(
            keywords = "\$acos \$acosh \$asin \$asinh \$assertcontrol \$assertfailoff \$assertfailon \$assertkill \$assertnonvacuouson \$assertoff \$asserton \$assertpassoff \$assertpasson \$assertvacuousoff \$async\$and\$array \$async\$and\$plane \$async\$nand\$array \$async\$nand\$plane \$async\$nor\$array \$async\$nor\$plane \$async\$or\$array \$async\$or\$plane \$atan \$atan2 \$atanh \$bits \$bitstoreal \$bitstoshortreal \$cast \$ceil \$changed \$changed_gclk \$changing_gclk \$clog2 \$cos \$cosh \$countbits \$countones \$coverage_control \$coverage_get \$coverage_get_max \$coverage_merge \$coverage_save \$dimensions \$display \$displayb \$displayh \$displayo \$dist_chi_square \$dist_erlang \$dist_exponential \$dist_normal \$dist_poisson \$dist_t \$dist_uniform \$dumpall \$dumpfile \$dumpflush \$dumplimit \$dumpoff \$dumpon \$dumpports \$dumpportsall \$dumpportsflush \$dumpportslimit \$dumpportsoff \$dumpportson \$dumpvars \$error \$exit \$exp \$falling_gclk \$fatal \$fclose \$fdisplay \$fdisplayb \$fdisplayh \$fdisplayo \$fell \$fell_gclk \$feof \$ferror \$fflush \$fgetc \$fgets \$finish \$floor \$fmonitor \$fmonitorb \$fmonitorh \$fmonitoro \$fopen \$fread \$fscanf \$fseek \$fstrobe \$fstrobeb \$fstrobeh \$fstrobeo \$ftell \$future_gclk \$fwrite \$fwriteb \$fwriteh \$fwriteo \$get_coverage \$high \$hypot \$increment \$info \$isunbounded \$isunknown \$itor \$left \$ln \$load_coverage_db \$log10 \$low \$monitor \$monitorb \$monitorh \$monitoro \$onehot \$onehot0 \$past \$past_gclk \$pow \$printtimescale \$psprintf \$q_add \$q_exam \$q_full \$q_initialize \$q_remove \$random \$readmemb \$readmemh \$realtime \$realtobits \$rewind \$right \$rising_gclk \$rose \$rose_gclk \$rtoi \$sampled \$sformat \$sformatf \$shortrealtobits \$signed \$sin \$sinh \$size \$sqrt \$sscanf \$stable \$stable_gclk \$steady_gclk \$stime \$stop \$strobe \$strobeb \$strobeh \$strobeo \$swrite \$swriteb \$swriteh \$swriteo \$sync\$and\$array \$sync\$and\$plane \$sync\$nand\$array \$sync\$nand\$plane \$sync\$nor\$array \$sync\$nor\$plane \$sync\$or\$array \$sync\$or\$plane \$system \$tan \$tanh \$time \$timeformat \$typename \$ungetc \$unpacked_dimensions \$unsigned \$value\$plusargs \$warning \$write \$writeb \$writeh \$writememb \$writememh \$writeo __FILE__ __LINE__ accept_on alias always always_comb always_ff always_latch and assert assign assume automatic before begin bind bins binsof bit break buf bufif0 bufif1 byte case casex casez cell chandle checker class clocking cmos config const constraint context continue cover covergroup coverpoint cross deassign default defparam design disable dist do edge else end endcase endchecker endclass endclocking endconfig endfunction endgenerate endgroup endinterface endmodule endpackage endprimitive endprogram endproperty endsequence endspecify endtable endtask enum event eventually expect export extends extern final first_match for force foreach forever fork forkjoin function generate genvar global highz0 highz1 if iff ifnone ignore_bins illegal_bins implements implies import incdir include initial inout input inside instance int integer interconnect interface intersect join join_any join_none large let liblist library local localparam logic longint macromodule matches medium modport module nand negedge nettype new nexttime nmos nor noshowcancelled not notif0 notif1 null or output package packed parameter pmos posedge primitive priority program property protected pull0 pull1 pulldown pullup pulsestyle_ondetect pulsestyle_onevent pure rand randc randcase randsequence rcmos real realtime ref reg reject_on release repeat restrict return rnmos rpmos rtran rtranif0 rtranif1 s_always s_eventually s_nexttime s_until s_until_with scalared sequence shortint shortreal showcancelled signed small soft solve specify specparam static string strong strong0 strong1 struct super supply0 supply1 sync_accept_on sync_reject_on table tagged task this throughout time timeprecision timeunit tran tranif0 tranif1 tri tri0 tri1 triand trior trireg type typedef union unique unique0 unsigned until until_with untyped use uwire var vectored virtual void wait wait_order wand weak weak0 weak1 while wildcard wire with within wor xnor xor",
            lineComment = "//",
            blockOpen = "/*",
            blockClose = "*/",
            quotes = "\"",
            tripleQuoted = false,
        )
        "xml" -> LanguageSpec(
            keywords = "script style",
            lineComment = null,
            blockOpen = "<!--",
            blockClose = "-->",
            quotes = "\"'",
            tripleQuoted = false,
        )
        "yaml" -> LanguageSpec(
            keywords = "",
            lineComment = "#",
            blockOpen = null,
            blockClose = null,
            quotes = "\"'",
            tripleQuoted = false,
        )
        else -> null
    }
}
