/**
 * Handles the core configuration of the Robin application.
 *
 * <p>Provides the configuration foundation and utilities.
 * <br>Also provides accessors for components.
 *
 * <p>The Log4j2 XML filename can be configured via properties.json5 or a system property called <i>log4j2</i>.
 * <br><b>Example:</b>
 * <pre>java -jar robin.jar --server cfg/ -Dlog4j2=log4j2custom.xml</pre>
 *
 * <p>The properties.json5 filename can be configured via a system property called <i>properties</i>.
 * <br><b>Example:</b>
 * <pre>java -jar robin.jar --server cfg/ -Dproperties=properties-new.json5</pre>
 */
package com.mimecast.robin.config;
