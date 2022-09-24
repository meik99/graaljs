local common = import 'common.jsonnet';
local graalJs = import 'graal-js/ci.jsonnet';
local graalNodeJs = import 'graal-nodejs/ci.jsonnet';

{
  // Used to run fewer jobs
  local useOverlay = true,

  local overlay = 'f0b83fcf35a4cc24c092649a82fe21b9e0cd7425',

  local no_overlay = 'cb733e564850cd37b685fcef6f3c16b59802b22c',

  overlay: if useOverlay then overlay else no_overlay,

  specVersion: "3",

  local deployBinary = {
    setup+: [
      ['mx', '-p', 'graal-nodejs', 'sversions'],
      ['mx', '-p', 'graal-nodejs', 'build', '--force-javac'],
    ],
    run+: [
      ['mx', '-p', 'graal-js', 'deploy-binary-if-master', '--skip-existing', 'graaljs-lafo'],
      ['mx', '-p', 'graal-nodejs', 'deploy-binary-if-master', '--skip-existing', 'graalnodejs-lafo'],
    ],
    timelimit: '30:00',
  },

  builds: graalJs.builds + graalNodeJs.builds + [
    common.jdk11 + deployBinary + common.deploy + common.postMerge + common.ol65 + {name: 'js-deploybinary-ol65-amd64'},
    common.jdk11 + deployBinary + common.deploy + common.postMerge + common.darwin + {name: 'js-deploybinary-darwin-amd64', timelimit: '45:00'},
  ],

  // Set this flag to false to switch off the use of artifacts (pipelined builds).
  useArtifacts:: useOverlay,

  jobtemplate:: {
    defs:: $.defs,
    graalvm:: self.defs.ce,
    enabled:: self.graalvm.available,
    suiteimports+:: [],
    nativeimages+:: [],
    extraimagebuilderarguments+:: [],
    dynamicimports:: [self.graalvm.suites[s].dynamicimport for s in self.suiteimports],
    local di = std.join(',', self.dynamicimports),
    local ni = std.join(',', self.nativeimages),
    local eiba = std.join(' ', self.extraimagebuilderarguments),
    envopts:: (if std.length(di) > 0 then ['--dynamicimports=' + di] else [])
            + (if std.length(ni) > 0 then ['--native-images=' + ni] + ['--extra-image-builder-argument=' + a for a in self.extraimagebuilderarguments] else []),
    envvars:: {
      DYNAMIC_IMPORTS: di,
      NATIVE_IMAGES: ni,
      EXTRA_IMAGE_BUILDER_ARGUMENTS: eiba,
    },
    export_envvars:: [['set-export', key, self.envvars[key]] for key in std.objectFields(self.envvars) if std.length(self.envvars[key]) > 0],
    cd:: '',
    cd_run:: if self.cd != '' then [['cd', self.cd]] else [],
    graalvmtests:: '',
    graalvmtests_run:: if self.graalvmtests != '' then [
      ['git', 'clone', ['mx', 'urlrewrite', 'https://github.com/graalvm/graalvm-tests.git'], self.graalvmtests],
      ['git', '-C', self.graalvmtests, 'checkout', '75b6a9e16ebbfd8b9b0a24e4be7c4378e3281204'],
    ] else [],
    setup+: self.graalvm.setup,
    run+: []
      + self.export_envvars
      + self.cd_run
      + self.graalvmtests_run
      + (if std.length(self.cd_run) > 0 then [['mx', 'sversions']] else []),
    timelimit: "00:30:00",
  },

  defs:: {
    ce:: {
      edition:: 'ce',
      available:: true,

      graal_repo:: 'graal',
      suites:: {
        compiler:: {name:: 'compiler', dynamicimport:: '/' + self.name},
        vm:: {name:: 'vm', dynamicimport:: '/' + self.name},
        substratevm:: {name:: 'substratevm', dynamicimport:: '/' + self.name},
        tools:: {name:: 'tools', dynamicimport:: '/' + self.name},
        wasm:: {name:: 'wasm', dynamicimport:: '/' + self.name},
      },

      setup+: [
        // clone the imported revision of `graal`
        ['mx', '-p', 'graal-js', 'sforceimports'],
      ],
    },

    ee:: self.ce + {
      available:: false,
    },
  },

  ce: {defs:: $.defs, graalvm:: self.defs.ce},
  ee: {defs:: $.defs, graalvm:: self.defs.ee},

  local artifact_name(jdk, edition, os, arch, suffix='') =
    local desc = edition + "-" + jdk + "-" + os + "-" + arch + suffix;
    "js-graalvm-" + desc,

  local build_js_graalvm_artifact(build) =
    local jdk = build.jdk;
    local edition = build.graalvm.edition;
    local os = build.os;
    local arch = build.arch;
    local os_arch = os + (if arch == 'aarch64' then '_aarch64' else '') + (if os == 'windows' then '_' + jdk else '');
    local artifactName = artifact_name(jdk, edition, os, arch);
    self.jobtemplate + common[jdk] + common[os_arch] + {
    graalvm:: build.graalvm,
    suiteimports:: build.suiteimports,
    nativeimages:: build.nativeimages,
    name: "build-" + artifactName,
    run+: [
      ["mx", "-p", "graal-nodejs", "sversions"],
      ["mx", "-p", "graal-nodejs", "graalvm-show"],
      ["mx", "-p", "graal-nodejs", "build"],
    ],
    publishArtifacts+: [
      {
        name: artifactName,
        dir: "../",
        patterns: [
          "*/*/mxbuild",
          "*/mxbuild",
          "*/graal-nodejs/out", # js/graal-nodejs/out
        ],
      },
    ],
    timelimit: "00:30:00"
  },

  local use_js_graalvm_artifact(build) =
    local jdk = build.jdk;
    local edition = build.graalvm.edition;
    local os = build.os;
    local arch = build.arch;
    local artifactName = artifact_name(jdk, edition, os, arch);
    {
    environment+: {
      ARTIFACT_NAME: artifactName
    },
    requireArtifacts+: [
      {
        name: artifactName,
        dir: "../",
        autoExtract: false,
      },
    ],
    setup+: [
      ["unpack-artifact", "${ARTIFACT_NAME}"],
    ],
  },

  local isBuildUsingArtifact(build) = std.objectHasAll(build, 'artifact') && build.artifact != '',

  local applyArtifact(build) =
    if isBuildUsingArtifact(build) then build + use_js_graalvm_artifact(build) else build,

  local deriveArtifactBuilds(builds) =
    local buildKey(b) = artifact_name(b.jdk, b.graalvm.edition, b.os, b.arch);
    local buildsUsingArtifact = [a for a in builds if isBuildUsingArtifact(a)];
    [build_js_graalvm_artifact(b) for b in std.uniq(std.sort(buildsUsingArtifact, keyF=buildKey), keyF=buildKey)],

  local finishBuilds(allBuilds) =
    local builds = [b for b in allBuilds if !std.objectHasAll(b, 'enabled') || b.enabled];
    if self.useArtifacts then [applyArtifact(b) for b in builds] + deriveArtifactBuilds(builds) else builds,

  finishBuilds:: finishBuilds,
}
