#!/usr/bin/env python3
"""Fast stdlib-only contracts for the Rider-only build/release policy."""
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parents[2]

def properties():
    result={}
    for line in (ROOT/'gradle.properties').read_text().splitlines():
        if line and not line.lstrip().startswith('#') and '=' in line:
            key,value=line.split('=',1)
            result[key.strip()]=value.strip()
    return result

def jobs(workflow):
    section=re.split(r'^jobs:\s*$',workflow,maxsplit=1,flags=re.M)[1]
    starts=list(re.finditer(r'^  ([A-Za-z][A-Za-z0-9_-]*):\s*$',section,re.M))
    return {match.group(1):section[match.end():starts[index+1].start() if index+1<len(starts) else len(section)] for index,match in enumerate(starts)}

class RiderBuildPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gradle=(ROOT/'build.gradle.kts').read_text()
        cls.workflow=(ROOT/'.github/workflows/build.yml').read_text()
        cls.props=properties()
        cls.job_blocks=jobs(cls.workflow)

    def test_baseline_and_current_rider_versions_are_pinned_separately(self):
        version=self.props.get('riderVersion','')
        baseline=self.props.get('riderBaselineVersion','')
        pattern=r'^20\d{2}\.\d+(?:\.\d+){0,2}$'
        self.assertRegex(version,pattern)
        self.assertRegex(baseline,pattern)
        self.assertIn('providers.gradleProperty("riderVersion")',self.gradle)
        self.assertIn('providers.gradleProperty("riderBaselineVersion")',self.gradle)
        self.assertIn('rider(riderBaselineVersion.get())',self.gradle)
        self.assertNotIn('intellijIdea(',self.gradle)

    def test_minimum_build_is_explicit_and_shared_with_baseline_policy(self):
        self.assertRegex(self.props.get('riderBaselineBuild',''),r'^\d{3}\.\d+\.\d+$')
        self.assertIn('providers.gradleProperty("riderBaselineBuild")',self.gradle)
        self.assertNotIn('sinceBuild = "253"',self.gradle)

    def test_plugin_is_declared_rider_only(self):
        root=ET.parse(ROOT/'src/main/resources/META-INF/plugin.xml').getroot()
        dependencies={node.text for node in root.findall('depends') if node.get('optional')!='true'}
        self.assertIn('com.intellij.modules.rider',dependencies)

    def test_verifier_uses_same_pinned_current_rider_as_runtime(self):
        self.assertIn('create(IntelliJPlatformType.Rider, riderVersion.get())',self.gradle)
        self.assertNotIn('verificationType',self.gradle)
        self.assertNotIn('verificationVersion',self.gradle)
        self.assertNotIn('ProductRelease.Channel.EAP',self.gradle)
        self.assertNotIn('IntelliJPlatformType.IntellijIdea',self.gradle)

    def test_ci_has_no_matrix_or_second_build_verifier(self):
        self.assertEqual({'build','release'},set(self.job_blocks))
        self.assertNotRegex(self.workflow,re.compile(r'^\s*matrix:',re.M),msg='No compatibility matrix on any trigger')
        self.assertNotIn('${{ matrix.',self.workflow)
        self.assertNotIn('-PverificationType=',self.workflow)
        self.assertNotIn('-PverificationVersion=',self.workflow)

    def test_single_job_runs_tests_build_structure_and_binary_verifier(self):
        build=self.job_blocks['build']
        commands=re.findall(r'^\s*run:\s*(.*)$',build,re.M)
        combined=' '.join(commands)
        for task in ('check','buildPlugin','verifyPluginStructure','verifyPlugin'):
            self.assertRegex(combined,r'(?<![\w])'+re.escape(task)+r'(?![\w])')
        self.assertIn('python3 .github/scripts/test_rider_target.py',build)

    def test_compilation_baseline_and_current_runtime_use_correct_java(self):
        toolchain=re.search(r'jvmToolchain\((\d+)\)',self.gradle)
        self.assertIsNotNone(toolchain)
        java=re.search(r'java-version:\s*[\'"]?(\d+)',self.job_blocks['build'])
        self.assertIsNotNone(java)
        assert toolchain is not None
        assert java is not None
        self.assertEqual('21',toolchain.group(1),'2026.1 compilation baseline uses Java 21')
        self.assertEqual('25',java.group(1),'Current Rider runtime/verification uses Java 25')

    def test_behavioral_tests_target_current_rider_not_only_binary_verification(self):
        self.assertIn('testCurrentRider',self.gradle)
        self.assertRegex(self.gradle,r'type\s*=\s*IntelliJPlatformType\.Rider')
        self.assertRegex(self.gradle,r'version\s*(?:=|\.set\()\s*riderVersion')
        self.assertRegex(self.gradle,r'tasks\.check[\s\S]*dependsOn\([^\n]*testCurrentRider')
        self.assertIn('testWithoutMcp',self.gradle)
        self.assertGreaterEqual(self.gradle.count('systemProperty("codeStory.expectedRiderBuild"'),2)
        self.assertNotRegex(self.gradle,r'javaLauncher\s*=.*JavaLanguageVersion\.of\(21\)')
        proof=(ROOT/'src/test/kotlin/io/github/hubertrybak/codestory/RiderRuntimeTest.kt').read_text()
        self.assertIn('build.productCode',proof)
        self.assertIn('build.asStringWithoutProductCode()',proof)
        self.assertIn('Runtime.version().feature()',proof)
        self.assertIn('codeStory.expectedRiderBuild',proof)


    def test_release_still_requires_successful_verified_build_and_tag(self):
        release=self.job_blocks['release']
        self.assertRegex(release,re.compile(r'^\s*needs:\s*(?:\[\s*)?build(?:\s*\])?\s*$',re.M))
        self.assertIn("if: startsWith(github.ref, 'refs/tags/')",release)
        self.assertIn('contents: write',release)
        self.assertIn('actions/download-artifact@',release)
        self.assertIn('--verify-tag',release)
        self.assertIn('gh release create',release)

    def test_jobs_have_timeouts_and_reports_have_bounded_retention(self):
        for name,block in self.job_blocks.items():
            self.assertRegex(block,r'timeout-minutes:\s*\d+',name)
        self.assertGreaterEqual(len(re.findall(r'retention-days:\s*\d+',self.job_blocks['build'])),3)

    def test_documentation_declares_current_rider_not_broad_old_ide_support(self):
        readme=(ROOT/'README.md').read_text()
        requirements=readme.split('## Requirements',1)[1].split('\n## ',1)[0]
        self.assertIn('Rider',requirements)
        self.assertIn(self.props.get('riderBaselineVersion','NO_RIDER_BASELINE'),requirements)
        self.assertIn('riderVersion',readme)
        self.assertNotIn('Rider or IntelliJ IDEA 2025.3',readme)
        self.assertNotIn('Other IntelliJ-based IDEs from 2025.3',readme)
        self.assertNotIn('eight jobs',readme)

if __name__=='__main__':
    unittest.main(verbosity=2)
