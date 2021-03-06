/*
    Copyright (c) 2004-2012, The Dojo Foundation All Rights Reserved.
    Available via Academic Free License >= 2.1 OR the modified BSD license.
    see: http://dojotoolkit.org/license for details
*/
package org.dojotoolkit.optimizer.servlet.osgi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dojotoolkit.compressor.JSCompressorFactory;
import org.dojotoolkit.json.JSONParser;
import org.dojotoolkit.optimizer.JSOptimizerFactory;
import org.dojotoolkit.optimizer.servlet.JSFilter;
import org.dojotoolkit.optimizer.servlet.JSHandler;
import org.dojotoolkit.optimizer.servlet.JSServlet;
import org.dojotoolkit.server.util.osgi.OSGiResourceLoader;
import org.dojotoolkit.server.util.resource.ResourceLoader;
import org.dojotoolkit.server.util.rhino.RhinoClassLoader;
import org.eclipse.equinox.http.registry.HttpContextExtensionService;
import org.eclipse.equinox.http.servlet.ExtendedHttpService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.NamespaceException;
import org.osgi.util.tracker.ServiceTracker;

public class ZazlServicesTracker {
	private ServiceTracker httpServiceTracker = null;
	private ServiceTracker httpContextExtensionServiceTracker = null;
	private ServiceTracker jsCompressorFactoryTracker = null;
	private ServiceTracker jsOptimizerFactoryServiceTracker = null;
	private BundleContext context = null;
	private boolean registered = false;
	private ServiceReference httpServiceReference = null;
	private JSFilter jsFilter = null;
	private boolean useHTMLFilter = false;
	private String httpContextName = null;
	private List<ZazlServicesListener> listenerList = null;
	
	protected HttpContextExtensionService httpContextExtensionService = null;
	protected JSCompressorFactory jsCompressorFactory = null;
	protected JSOptimizerFactory jsOptimizerFactory = null;
	protected ExtendedHttpService httpService = null;
	protected HttpContext httpContext = null;

	public ZazlServicesTracker(String httpContextName) {
		this.httpContextName = httpContextName;
		listenerList = new ArrayList<ZazlServicesListener>();
	}
	
	public void start(BundleContext context) {
		this.context = context;
		httpServiceTracker = new HttpServiceTracker(context);
		httpServiceTracker.open();
		httpContextExtensionServiceTracker = new HttpContextExtensionServiceTracker(context);
		httpContextExtensionServiceTracker.open();
		boolean useV8 = Boolean.valueOf(System.getProperty("V8", "false"));
		String compressorType = System.getProperty("compressorType");
		if (compressorType != null && !compressorType.equals("none")) {
			jsCompressorFactoryTracker = new JSCompressorFactoryServiceTracker(context, useV8, compressorType);
			jsCompressorFactoryTracker.open();
		}
		jsOptimizerFactoryServiceTracker = new JSOptimizerFactoryServiceTracker(context, useV8, System.getProperty("jsHandlerType"));
		jsOptimizerFactoryServiceTracker.open();
	}
	
	public void stop() {
		httpContextExtensionServiceTracker.close();
		httpContextExtensionServiceTracker = null;
		httpServiceTracker.close();
		httpServiceTracker = null;
		if (jsCompressorFactoryTracker != null) {
			jsCompressorFactoryTracker.close();
			jsCompressorFactoryTracker = null;
		}
		jsOptimizerFactoryServiceTracker.close();
		jsOptimizerFactoryServiceTracker = null;
		this.context = null;
	}
	
	public void addListener(ZazlServicesListener listener) {
		listenerList.add(listener);
	}
	
	public void removeListener(ZazlServicesListener listener) {
		listenerList.remove(listener);
	}
	
	protected boolean register() {
		boolean compressorReady = (jsCompressorFactoryTracker == null) ? true : jsCompressorFactory != null;
		if (!registered && httpService != null && httpContextExtensionService != null && compressorReady && jsOptimizerFactory != null) {
			httpContext = httpContextExtensionService.getHttpContext(httpServiceReference, httpContextName);
			if (httpContext == null) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				httpContext = httpContextExtensionService.getHttpContext(httpServiceReference, httpContextName);
				if (httpContext == null) {
					System.out.println("Unable to obtain HttpContext for "+httpContextName);
					return registered;
				}
			}
			List<String> bundleIdList = new ArrayList<String>();
			String bundleIdsString = System.getProperty("searchBundleIds");
			if (bundleIdsString != null) {
				StringTokenizer st = new StringTokenizer(bundleIdsString, ",");
				while (st.hasMoreTokens()) {
					bundleIdList.add(st.nextToken().trim());
				}
			}
			String[] bundleIds = new String[bundleIdList.size()];
			bundleIds = bundleIdList.toArray(bundleIds);
			ResourceLoader resourceLoader = new OSGiResourceLoader(context, bundleIds);
			RhinoClassLoader rhinoClassLoader = new RhinoClassLoader(resourceLoader);
			String jsHandlerType = System.getProperty("jsHandlerType");
			
			String rhinoJSClassesString = System.getProperty("rhinoJSClasses");
			List<String> rhinoJSClasses = null;
			if (rhinoJSClassesString != null) {
				try {
					rhinoJSClasses = (List<String>)JSONParser.parse(new StringReader(rhinoJSClassesString));
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			boolean useTimestamps = true;
			String useTimestampsString = System.getProperty("useTimestamps");
			if (useTimestampsString != null && useTimestampsString.equalsIgnoreCase("false")) {
				useTimestamps = false;
			}
			JSServlet jsServlet = new JSServlet(resourceLoader, jsOptimizerFactory, rhinoClassLoader, jsHandlerType, null, rhinoJSClasses, jsCompressorFactory, useTimestamps);
			useHTMLFilter = Boolean.valueOf(System.getProperty("useHTMLFilter", "false"));
			if (useHTMLFilter) {
				jsFilter = new JSFilter(resourceLoader, rhinoClassLoader);
			}
			try {
				System.out.println("Registering servlets");
				httpService.registerServlet("/_javascript", jsServlet, null, httpContext);
				httpService.registerServlet("/", new ResourceServlet(resourceLoader), null, httpContext);
				if (useHTMLFilter) {
					System.out.println("Registering HTML Filter");
					httpService.registerFilter("/*.html", jsFilter, null, httpContext);
				}
				registered = true;
				for (ZazlServicesListener listener : listenerList) {
					listener.servicesAvailable(httpService, httpContextExtensionService, jsCompressorFactory, jsOptimizerFactory, httpContext);
				}
			} catch (ServletException e) {
				e.printStackTrace();
			} catch (NamespaceException e) {
				e.printStackTrace();
			}
		}
		return registered;
	}
	
	private class HttpServiceTracker extends ServiceTracker {
		public HttpServiceTracker(BundleContext context) {
			super(context, ExtendedHttpService.class.getName(), null);
		}

		public Object addingService(ServiceReference reference) {
			httpServiceReference = reference;
			httpService = (ExtendedHttpService) context.getService(reference);
			register();
			return httpService;
		}

		public void removedService(ServiceReference reference, Object service) {
			final ExtendedHttpService httpService = (ExtendedHttpService) service;
			if (registered) {
				httpService.unregister("/_javascript");
				httpService.unregister("/");
				if (useHTMLFilter) {
					httpService.unregisterFilter(jsFilter);
				}
			}
			super.removedService(reference, service);
		}			
	}
	
	private class HttpContextExtensionServiceTracker extends ServiceTracker {
		public HttpContextExtensionServiceTracker(BundleContext context) {
			super(context, HttpContextExtensionService.class.getName(), null);
		}
		
		public Object addingService(ServiceReference reference) {
			httpContextExtensionService = (HttpContextExtensionService)context.getService(reference);
			register();
			return httpContextExtensionService;
		}
	}
	
	private class JSCompressorFactoryServiceTracker extends ServiceTracker {
		private boolean useV8 = false; 
		private String compressorType = null;
		
		public JSCompressorFactoryServiceTracker(BundleContext context, boolean useV8, String compressorType) {
			super(context, JSCompressorFactory.class.getName(), null);
			this.useV8 = useV8;
			this.compressorType = compressorType;
		}
		
		public Object addingService(ServiceReference reference) {
			String dojoServiceId = null;
			if (compressorType != null) {
				if (compressorType.equals("shrinksafe")) {
					dojoServiceId = "ShrinksafeJSCompressor";
				} else if (compressorType.equals("uglifyjs")) {
					if (useV8) {
						dojoServiceId = "V8UglifyJSCompressor";
					} else {
						dojoServiceId = "RhinoUglifyJSCompressor";
					}
				} else {
					dojoServiceId = compressorType;
				}
			}
			if (dojoServiceId != null && reference.getProperty("dojoServiceId").equals(dojoServiceId)) {
				jsCompressorFactory = (JSCompressorFactory)context.getService(reference);
				register();
			}
			return context.getService(reference);
		}
	}
	
	private class JSOptimizerFactoryServiceTracker extends ServiceTracker {
		private boolean useV8 = false;
		private String jsHandlerType = null;
		
		public JSOptimizerFactoryServiceTracker(BundleContext context, boolean useV8, String jsHandlerType) {
			super(context, JSOptimizerFactory.class.getName(), null);
			this.useV8 = useV8;
			this.jsHandlerType = jsHandlerType;
		}
		
		public Object addingService(ServiceReference reference) {
			String dojoServiceId = null;
			if (jsHandlerType.equals(JSHandler.SYNCLOADER_HANDLER_TYPE)) {
				if (useV8) {
					dojoServiceId = "V8JSOptimizer";
				} else {
					dojoServiceId = "RhinoJSOptimizer";
				}
			} else {
				if (useV8) {
					dojoServiceId = "AMDV8JSOptimizer";
				} else {
					dojoServiceId = System.getProperty("optimizerId", "AMDRhinoASTJSOptimizer");
				}
			}
			if (dojoServiceId != null && reference.getProperty("dojoServiceId").equals(dojoServiceId)) {
				jsOptimizerFactory = (JSOptimizerFactory)context.getService(reference);
				register();
			}
			return context.getService(reference);
		}
	}
	
	public class ResourceServlet extends HttpServlet {
		private static final long serialVersionUID = 1L;
		private ResourceLoader resourceLoader = null;
		
		public ResourceServlet(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}

		protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
			String target = request.getPathInfo();
			URL url = resourceLoader.getResource(target);
			if (url != null) {
				String mimeType = getServletContext().getMimeType(target);
				if (mimeType == null) {
					mimeType = "text/plain";
				}
				response.setContentType(mimeType);
				InputStream is = null;
				URLConnection urlConnection = null;
				OutputStream os = null;
				os = response.getOutputStream();
				try {
					urlConnection = url.openConnection();
					is = urlConnection.getInputStream();
					long lastModifed = urlConnection.getLastModified();
					if (lastModifed > 0) {
					    String ifNoneMatch = request.getHeader("If-None-Match");
						
					    if (ifNoneMatch != null && ifNoneMatch.equals(Long.toString(lastModifed))) {
					    	response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
					        return;
					    }

			 			response.setHeader("ETag", Long.toString(lastModifed));
					}
					byte[] buffer = new byte[4096];
					int len = 0;
					while((len = is.read(buffer)) != -1) {
						os.write(buffer, 0, len);
					}
				}
				finally {
					if (is != null) {try{is.close();}catch(IOException e){}}
				}
			}
			else {
				response.sendError(HttpServletResponse.SC_NOT_FOUND, "path ["+target+"] not found");
			}
		}
	}
	
	public interface ZazlServicesListener {
		public void servicesAvailable(ExtendedHttpService httpService, 
				                      HttpContextExtensionService httpContextExtensionService,
				                      JSCompressorFactory jsCompressorFactory,
				                      JSOptimizerFactory jsOptimizerFactory,
				                      HttpContext httpContext);
	}
}
