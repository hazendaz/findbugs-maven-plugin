/*
 * Copyright (C) 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

File spotbugsHtml =  new File(basedir, 'target/site/spotbugs.html')
assert !spotbugsHtml.exists()

File spotbugXdoc = new File(basedir, 'target/spotbugs.xml')
assert spotbugXdoc.exists()

File spotbugXml = new File(basedir, 'target/spotbugsXml.xml')
assert spotbugXml.exists()

println '**********************************'
println "Checking Spotbugs Native XML file"
println '**********************************'

path = new XmlSlurper().parse(spotbugXml)

allNodes = path.depthFirst().collect{ it }
def spotbugsXmlErrors = allNodes.findAll {it.name() == 'BugInstance'}.size()
println "BugInstance size is ${spotbugsXmlErrors}"



println '***************************'
println "Checking xDoc file"
println '***************************'

def path = new XmlSlurper().parse(spotbugXdoc)

xNodes = path.depthFirst().collect{ it }
def xdocErrors = xNodes.findAll {it.name() == 'BugInstance'}.size()
println "BugInstance size is ${xdocErrors}"

assert xdocErrors == spotbugsXmlErrors

