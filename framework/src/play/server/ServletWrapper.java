package play.server;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;

import play.Invoker;
import play.Invoker.InvocationContext;
import play.Logger;
import play.Play;
import play.data.binding.CachedBoundActionMethodArgs;
import play.data.validation.Validation;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.libs.MimeTypes;
import play.mvc.ActionInvoker;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Router;
import play.mvc.Scope;
import play.mvc.results.NotFound;
import play.mvc.results.RenderStatic;
import play.server.Throttle.Result;
import play.templates.TemplateLoader;
import play.utils.Utils;
import play.vfs.VirtualFile;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.*;

import static org.apache.commons.io.IOUtils.closeQuietly;

/**
 * Servlet implementation.
 * Thanks to Lee Breisacher.
 */
public class ServletWrapper extends HttpServlet implements ServletContextListener {
	
	private static final Throttle throttle =  new Throttle(20,Throttle.Timespan.Hour);

	private static boolean ignoreError(Exception e, Scope.Session s, Http.Request r, Scope.Flash f, Scope.Params p, boolean is500){
		String url = r==null || r.url==null ? "undefined":r.url.trim().toLowerCase();
		int i = url.indexOf('?');
		String obj=i>-1?url.substring(0, i):url;
		String qry=i>-1?url.substring(i+1):"";
		
		if(!is500){
			//404
            return true;
			/*if(url.equals("/_stax/status")) return true;
			String[] endings = new String[]{
				"php","css","js","jsp","asp","htm","html","ico","jpeg","jpg","gif","png","tiff","pdf"	
			};
			for(String ending:endings) if(obj.endsWith("."+ending)) return true;
			
			String[] contains = new String[]{
					"cgi","-bin","php","mysql",
					"muieblackcat", 
					"data:image/jpeg;base64"
			};
			for(String cnts:contains) if(url.contains(cnts)) return true;
			
			String[] eqs = new String[]{
					"/index.action", "/manager/html","/headers"
			};
			for(String eq:eqs) if(url.equalsIgnoreCase(eq)) return true;
			*/
		} else {
			//500
			if(
			         e==null || e.getLocalizedMessage()==null ||
                            e.getLocalizedMessage().equals("Unexpected Error") && url.equals("/files/upload")) return true;
			
			
		}
		return false;
	}
	
	private static String printStackTrace(Exception e){
		try{
		 StringWriter stack = new StringWriter();
		 e.printStackTrace(new PrintWriter(stack)); 
		 return stack.toString();
		} catch(Exception e2){
			 return "";
		 }
	}
	
	//Play/server/ServletWrapper.java called from serve500 and serve404
	private static void reportError(Exception e, play.mvc.Scope.Session s, play.mvc.Http.Request r, play.mvc.Scope.Flash f, play.mvc.Scope.Params p, boolean is500){
		
		Result tr = Result.Ok;
		
		StringBuilder m0 = new StringBuilder();
		try{		
			if( ignoreError(e,s,r,f,p,is500)) {
				play.Logger.warn("REPORTERRORCUSTOM report error ignore"); //return; 
				if(is500) {m0.append(" WOUL_BE_IGNORED "); return;} else return;
			};
		} catch (Exception e2){
			m0.append("Exception in ignoreError "+e2.getLocalizedMessage()+"\n"+printStackTrace(e2));
		}
		

		
		try{
			tr = throttle.get();
			if(!tr.get()) {
				play.Logger.warn("REPORTERRORCUSTOM report error throttle");
				m0.append(" WOUL_BE_THROTTLED ");
				return;
			}
		} catch (Exception e3){
			m0.append("Exception in throttle "+e3.getLocalizedMessage()+"\n"+printStackTrace(e3));
		}
		

    	
		String eId = "";
		//if(e instanceof PlayException) eId=((PlayException) e).getId();
		StringBuilder m = new StringBuilder();
		m.append(m0).append("\n");
		
		try{
	    	m.append(e.getLocalizedMessage()+"\n\n");
	    	if(s!=null && s.all()!=null){
	    		m.append("Session:\n");
	    		for(String k: s.all().keySet()){
	    			String v = s.all().get(k);
	    			if(v!=null) m.append(k+": "+v+"\n");
	    		}
	    	}
	    	if(r!=null){
	    		m.append("\n\nRequest:\n"+r.url+"\n\n");
	    		if(r.headers!=null){
	    			for(String k: r.headers.keySet()){
	    				m.append(k+": ");
	    				if(r.headers.get(k)!=null){
	    					m.append(r.headers.get(k).name+" ");
	    					for(String v:r.headers.get(k).values){
	    						m.append(v+"\n");
	    	    			}
	    					m.append("\n");
	    				}
	    			}
	    		}
	    	}
	    	if(f!=null){
	    		m.append("Flash:"+f.toString()+"\n");
	    	}
	    	if(p!=null & p.data!=null){
	    		m.append("Params:\n");
	    		for(String k:p.data.keySet()){
	    			m.append(k+": ");
	    			for(String v:p.data.get(k)){
	    				m.append(v+"\n");
	    			};
	    			m.append("\n");
	    		}
	    	}
		} catch (Exception e4){
			m.append("\nError when getting request details "+e.getLocalizedMessage()+"\n"+printStackTrace(e));
		}
	

		Properties prop  = Play.configuration;
    	String errorMonitorigType = prop.getProperty("errormonitoring.type","none");

    	if(errorMonitorigType.equalsIgnoreCase("email")){

	        	try{
	    			SimpleEmail emailer = new SimpleEmail();
	    			emailer.setHostName(prop.getProperty("errormonitoring.smtphost","smtp.gmail.com"));
	    			emailer.setAuthentication(prop.getProperty("errormonitoring.user",""),prop.getProperty("errormonitoring.password",""));
	    			emailer.setSSL((prop.getProperty("errormonitoring.emailssl","true").equalsIgnoreCase("true")));
	    			emailer.setSslSmtpPort(prop.getProperty("errormonitoring.port","465"));
	    			emailer.setMsg(m.toString());
	    			emailer.setFrom(prop.getProperty("errormonitoring.emailfrom",""));
	    			emailer.addTo(prop.getProperty("errormonitoring.emailto",""));
	    			emailer.setSubject(prop.getProperty("application.name","Play!")+" Error "+(is500?"500 ":"404 ")+eId+((tr==Throttle.Result.Last)?" Next Error and Not Found Monitoring Emails [TTA Throttled] for this Hour":""));
	    			emailer.send();
	        	}
	        	catch (EmailException e1){
	        		play.Logger.error("REPORTERRORCUSTOM6 Error sending monitoring email", e1);
	        	}
    	}
    }		
	
    public static final String IF_MODIFIED_SINCE = "If-Modified-Since";
    public static final String IF_NONE_MATCH = "If-None-Match";

    /**
     * Constant for accessing the underlying HttpServletRequest from Play's Request
     * in a Servlet based deployment.
     * <p>Sample usage:</p>
     * <p> {@code HttpServletRequest req = Request.current().args.get(ServletWrapper.SERVLET_REQ);}</p>
     */
    public static final String SERVLET_REQ = "__SERVLET_REQ";
    /**
     * Constant for accessing the underlying HttpServletResponse from Play's Request
     * in a Servlet based deployment.
     * <p>Sample usage:</p>
     * <p> {@code HttpServletResponse res = Request.current().args.get(ServletWrapper.SERVLET_RES);}</p>
     */
    public static final String SERVLET_RES = "__SERVLET_RES";

    private static boolean routerInitializedWithContext = false;

    public void contextInitialized(ServletContextEvent e) {
        Play.standalonePlayServer = false;
        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        String appDir = e.getServletContext().getRealPath("/WEB-INF/application");
        File root = new File(appDir);
        final String playId = System.getProperty("play.id", e.getServletContext().getInitParameter("play.id"));
        if (StringUtils.isEmpty(playId)) {
            throw new UnexpectedException("Please define a play.id parameter in your web.xml file. Without that parameter, play! cannot start your application. Please add a context-param into the WEB-INF/web.xml file.");
        }
        // This is really important as we know this parameter already (we are running in a servlet container)
        Play.frameworkPath = root.getParentFile();
        Play.usePrecompiled = true;
        Play.init(root, playId);
        Play.Mode mode = Play.Mode.valueOf(Play.configuration.getProperty("application.mode", "DEV").toUpperCase());
        if (mode.isDev()) {
            Logger.info("Forcing PROD mode because deploying as a war file.");
        }

        // Servlet 2.4 does not allow you to get the context path from the servletcontext...
        if (isGreaterThan(e.getServletContext(), 2, 4)) {
            loadRouter(e.getServletContext().getContextPath());
        }

        Thread.currentThread().setContextClassLoader(oldClassLoader);
    }

    public void contextDestroyed(ServletContextEvent e) {
        Play.stop();
    }

    @Override
    public void destroy() {
        Logger.trace("ServletWrapper>destroy");
        Play.stop();
    }

    private static synchronized void loadRouter(String contextPath) {
        // Reload the rules, but this time with the context. Not really efficient through...
        // Servlet 2.4 does not allow you to get the context path from the servletcontext...
        if (routerInitializedWithContext) return;
        Play.ctxPath = contextPath;
        Router.load(contextPath);
        routerInitializedWithContext = true;
    }

    public static boolean isGreaterThan(ServletContext context, int majorVersion, int minorVersion) {
        int contextMajorVersion = context.getMajorVersion();
        int contextMinorVersion = context.getMinorVersion();
        return (contextMajorVersion > majorVersion) || (contextMajorVersion == majorVersion && contextMinorVersion > minorVersion);
    }

    @Override
    protected void service(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {

        if (!routerInitializedWithContext) {
            loadRouter(httpServletRequest.getContextPath());
        }

        if (Logger.isTraceEnabled()) {
            Logger.trace("ServletWrapper>service " + httpServletRequest.getRequestURI());
        }

        Request request = null;
        try {
            Response response = new Response();
            response.out = new ByteArrayOutputStream();
            Response.current.set(response);
            request = parseRequest(httpServletRequest);

            if (Logger.isTraceEnabled()) {
                Logger.trace("ServletWrapper>service, request: " + request);
            }

            boolean raw = Play.pluginCollection.rawInvocation(request, response);
            if (raw) {
                copyResponse(Request.current(), Response.current(), httpServletRequest, httpServletResponse);
            } else {
                Invoker.invokeInThread(new ServletInvocation(request, response, httpServletRequest, httpServletResponse));
            }
        } catch (NotFound e) {
            if (Logger.isTraceEnabled()) {
                Logger.trace("ServletWrapper>service, NotFound: " + e);
            }
            serve404(httpServletRequest, httpServletResponse, e);
            return;
        } catch (RenderStatic e) {
            if (Logger.isTraceEnabled()) {
                Logger.trace("ServletWrapper>service, RenderStatic: " + e);
            }
            serveStatic(httpServletResponse, httpServletRequest, e);
            return;
        } catch(URISyntaxException e) {
			 serve404(httpServletRequest, httpServletResponse, new NotFound(e.toString()));
	         return;
        } catch (Throwable e) {
            throw new ServletException(e);
        } finally {
            Request.current.remove();
            Response.current.remove();
            Scope.Session.current.remove();
            Scope.Params.current.remove();
            Scope.Flash.current.remove();
            Scope.RenderArgs.current.remove();
            Scope.RouteArgs.current.remove();
            CachedBoundActionMethodArgs.clear();
        }
    }

    public void serveStatic(HttpServletResponse servletResponse, HttpServletRequest servletRequest, RenderStatic renderStatic) throws IOException {

        VirtualFile file = Play.getVirtualFile(renderStatic.file);
        if (file == null || file.isDirectory() || !file.exists()) {
            serve404(servletRequest, servletResponse, new NotFound("The file " + renderStatic.file + " does not exist"));
        } else {
            servletResponse.setContentType(MimeTypes.getContentType(file.getName()));
            boolean raw = Play.pluginCollection.serveStatic(file, Request.current(), Response.current());
            if (raw) {
                copyResponse(Request.current(), Response.current(), servletRequest, servletResponse);
            } else {
                if (Play.mode == Play.Mode.DEV) {
                    servletResponse.setHeader("Cache-Control", "no-cache");
                    servletResponse.setHeader("Content-Length", String.valueOf(file.length()));
                    if (!servletRequest.getMethod().equals("HEAD")) {
                        copyStream(servletResponse, file.inputstream());
                    } else {
                        copyStream(servletResponse, new ByteArrayInputStream(new byte[0]));
                    }
                } else {
                    long last = file.lastModified();
                    String etag = "\"" + last + "-" + file.hashCode() + "\"";
                    String lastDate = Utils.getHttpDateFormatter().format(new Date(last));
                    if (!isModified(etag, lastDate, servletRequest)) {
                        servletResponse.setHeader("Etag", etag);
                        servletResponse.setStatus(304);
                    } else {
                        servletResponse.setHeader("Last-Modified", lastDate);
                        servletResponse.setHeader("Cache-Control", "max-age=" + Play.configuration.getProperty("http.cacheControl", "3600"));
                        servletResponse.setHeader("Etag", etag);
                        copyStream(servletResponse, file.inputstream());
                    }
                }
            }
        }
    }

    public static boolean isModified(String etag, String lastDate,
            HttpServletRequest request) {
        // See section 14.26 in rfc 2616 http://www.faqs.org/rfcs/rfc2616.html
        String browserEtag = request.getHeader(IF_NONE_MATCH);
        String dateString = request.getHeader(IF_MODIFIED_SINCE);
        if (browserEtag != null) {
            boolean etagMatches = browserEtag.equals(etag);
            if (!etagMatches) {
                return true;
            }
            if (dateString != null) {
                return !isValidTimeStamp(lastDate, dateString);
            }
            return false;
        } else {
            if (dateString != null) {
                return !isValidTimeStamp(lastDate, dateString);
            } else {
                return true;
            }
        }
    }

    private static boolean isValidTimeStamp(String lastDateString, String dateString) {
        try {
            long browserDate = Utils.getHttpDateFormatter().parse(dateString).getTime();
            long lastDate = Utils.getHttpDateFormatter().parse(lastDateString).getTime();
            return browserDate >= lastDate;
        } catch (ParseException e) {
            Logger.error("Can't parse date", e);
            return false;
        }
    }

    public static Request parseRequest(HttpServletRequest httpServletRequest) throws Exception {
	 	
		URI uri = new URI(httpServletRequest.getRequestURI());
        String method = httpServletRequest.getMethod().intern();
        String path = uri.getPath();
        String querystring = httpServletRequest.getQueryString() == null ? "" : httpServletRequest.getQueryString();

        if (Logger.isTraceEnabled()) {
            Logger.trace("httpServletRequest.getContextPath(): " + httpServletRequest.getContextPath());
            Logger.trace("request.path: " + path + ", request.querystring: " + querystring);
        }

        String contentType = null;
        if (httpServletRequest.getHeader("Content-Type") != null) {
            contentType = httpServletRequest.getHeader("Content-Type").split(";")[0].trim().toLowerCase().intern();
        } else {
            contentType = "text/html".intern();
        }

        if (httpServletRequest.getHeader("X-HTTP-Method-Override") != null) {
            method = httpServletRequest.getHeader("X-HTTP-Method-Override").intern();
        }

        InputStream body = httpServletRequest.getInputStream();
        boolean secure = httpServletRequest.isSecure();

        String url = uri.toString() + (httpServletRequest.getQueryString() == null ? "" : "?" + httpServletRequest.getQueryString());
        String host = httpServletRequest.getHeader("host");
        int port = 0;
        String domain = null;
        if (host.contains(":")) {
            port = Integer.parseInt(host.split(":")[1]);
            domain = host.split(":")[0];
        } else {
            port = 80;
            domain = host;
        }

        String remoteAddress = httpServletRequest.getRemoteAddr();

        boolean isLoopback = host.matches("^127\\.0\\.0\\.1:?[0-9]*$");


        final Request request = Request.createRequest(
                remoteAddress,
                method,
                path,
                querystring,
                contentType,
                body,
                url,
                host,
                isLoopback,
                port,
                domain,
                secure,
                getHeaders(httpServletRequest),
                getCookies(httpServletRequest));


        Request.current.set(request);
        Router.routeOnlyStatic(request);

        return request;
    }

    protected static Map<String, Http.Header> getHeaders(HttpServletRequest httpServletRequest) {
        Map<String, Http.Header> headers = new HashMap<String, Http.Header>(16);

        Enumeration headersNames = httpServletRequest.getHeaderNames();
        while (headersNames.hasMoreElements()) {
            Http.Header hd = new Http.Header();
            hd.name = (String) headersNames.nextElement();
            hd.values = new ArrayList<String>();
            Enumeration enumValues = httpServletRequest.getHeaders(hd.name);
            while (enumValues.hasMoreElements()) {
                String value = (String) enumValues.nextElement();
                hd.values.add(value);
            }
            headers.put(hd.name.toLowerCase(), hd);
        }

        return headers;
    }

    protected static Map<String, Http.Cookie> getCookies(HttpServletRequest httpServletRequest) {
        Map<String, Http.Cookie> cookies = new HashMap<String, Http.Cookie>(16);
        javax.servlet.http.Cookie[] cookiesViaServlet = httpServletRequest.getCookies();
        if (cookiesViaServlet != null) {
            for (javax.servlet.http.Cookie cookie : cookiesViaServlet) {
                Http.Cookie playCookie = new Http.Cookie();
                playCookie.name = cookie.getName();
                playCookie.path = cookie.getPath();
                playCookie.domain = cookie.getDomain();
                playCookie.secure = cookie.getSecure();
                playCookie.value = cookie.getValue();
                playCookie.maxAge = cookie.getMaxAge();
                cookies.put(playCookie.name, playCookie);
            }
        }

        return cookies;
    }

    public void serve404(HttpServletRequest servletRequest, HttpServletResponse servletResponse, NotFound e) {
        Logger.warn("404 -> %s %s (%s)", servletRequest.getMethod(), servletRequest.getRequestURI(), e.getMessage());
        servletResponse.setStatus(404);
        servletResponse.setContentType("text/html");
        Map<String, Object> binding = new HashMap<String, Object>();
        binding.put("result", e);
        binding.put("session", Scope.Session.current());
        binding.put("request", Http.Request.current());
        binding.put("flash", Scope.Flash.current());
        binding.put("params", Scope.Params.current());
        binding.put("play", new Play());
        try {
            binding.put("errors", Validation.errors());
        } catch (Exception ex) {
            //
        }
        String format = Request.current().format;
        servletResponse.setStatus(404);
        // Do we have an ajax request? If we have then we want to display some text even if it is html that is requested
        if ("XMLHttpRequest".equals(servletRequest.getHeader("X-Requested-With")) && (format == null || format.equals("html"))) {
            format = "txt";
        }
        if (format == null) {
            format = "txt";
        }
        servletResponse.setContentType(MimeTypes.getContentType("404." + format, "text/plain"));
        String errorHtml = TemplateLoader.load("errors/404." + format).render(binding);
        try {
            servletResponse.getOutputStream().write(errorHtml.getBytes(Response.current().encoding));
        } catch (Exception fex) {
            Logger.error(fex, "(encoding ?)");
        }
        reportError(e,Scope.Session.current(),Http.Request.current(),Scope.Flash.current(),Scope.Params.current(),false);
    }

    public void serve500(Exception e, HttpServletRequest request, HttpServletResponse response) {
        try {
            Map<String, Object> binding = new HashMap<String, Object>();
            if (!(e instanceof PlayException)) {
                e = new play.exceptions.UnexpectedException(e);
            }
            // Flush some cookies
            try {
                Map<String, Http.Cookie> cookies = Response.current().cookies;
                for (Http.Cookie cookie : cookies.values()) {
                    if (cookie.sendOnError) {
                        Cookie c = new Cookie(cookie.name, cookie.value);
                        c.setSecure(cookie.secure);
                        c.setPath(cookie.path);
                        if (cookie.domain != null) {
                            c.setDomain(cookie.domain);
                        }
                        response.addCookie(c);
                    }
                }
            } catch (Exception exx) {
                // humm ?
            }
            binding.put("exception", e);
            binding.put("session", Scope.Session.current());
            binding.put("request", Http.Request.current());
            binding.put("flash", Scope.Flash.current());
            binding.put("params", Scope.Params.current());
            binding.put("play", new Play());
            try {
                binding.put("errors", Validation.errors());
            } catch (Exception ex) {
                //
            }
            response.setStatus(500);
            String format = "html";
            if (Request.current() != null) {
                format = Request.current().format;
            }
            // Do we have an ajax request? If we have then we want to display some text even if it is html that is requested
            if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With")) && (format == null || format.equals("html"))) {
                format = "txt";
            }
            if (format == null) {
                format = "txt";
            }
            response.setContentType(MimeTypes.getContentType("500." + format, "text/plain"));
            try {
                String errorHtml = TemplateLoader.load("errors/500." + format).render(binding);
                response.getOutputStream().write(errorHtml.getBytes(Response.current().encoding));
                Logger.error(e, "Internal Server Error (500)");
                reportError(e,Scope.Session.current(),Http.Request.current(),Scope.Flash.current(),Scope.Params.current(),true);
            } catch (Throwable ex) {
                Logger.error(e, "Internal Server Error (500)");
                Logger.error(ex, "Error during the 500 response generation");
                reportError(e,Scope.Session.current(),Http.Request.current(),Scope.Flash.current(),Scope.Params.current(),true);
                throw ex;
            }
        } catch (Throwable exxx) {
        	reportError(e,Scope.Session.current(),Http.Request.current(),Scope.Flash.current(),Scope.Params.current(),true);
            if (exxx instanceof RuntimeException) {
                throw (RuntimeException) exxx;
            }
            throw new RuntimeException(exxx);
        }
    }

    public void copyResponse(Request request, Response response, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException {
        String encoding = Response.current().encoding;
        if (response.contentType != null) {
            servletResponse.setHeader("Content-Type", response.contentType + (response.contentType.startsWith("text/") ? "; charset="+encoding : ""));
        } else {
            servletResponse.setHeader("Content-Type", "text/plain;charset="+encoding);
        }

        servletResponse.setStatus(response.status);
        if (!response.headers.containsKey("cache-control")) {
            servletResponse.setHeader("Cache-Control", "no-cache");
        }
        Map<String, Http.Header> headers = response.headers;
        for (Map.Entry<String, Http.Header> entry : headers.entrySet()) {
            Http.Header hd = entry.getValue();
            String key = entry.getKey();
            for (String value : hd.values) {
                servletResponse.setHeader(key, value);
            }
        }

        Map<String, Http.Cookie> cookies = response.cookies;
        for (Http.Cookie cookie : cookies.values()) {
            Cookie c = new Cookie(cookie.name, cookie.value);
            c.setSecure(cookie.secure);
            c.setPath(cookie.path);
            if (cookie.domain != null) {
                c.setDomain(cookie.domain);
            }
            if (cookie.maxAge != null) {
                c.setMaxAge(cookie.maxAge);
            }
            servletResponse.addCookie(c);
        }

        // Content

        response.out.flush();
        if (response.direct != null && response.direct instanceof File) {
            File file = (File) response.direct;
            servletResponse.setHeader("Content-Length", String.valueOf(file.length()));
            if (!request.method.equals("HEAD")) {
                copyStream(servletResponse, VirtualFile.open(file).inputstream());
            } else {
                copyStream(servletResponse, new ByteArrayInputStream(new byte[0]));
            }
        } else if (response.direct != null && response.direct instanceof InputStream) {
            copyStream(servletResponse, (InputStream) response.direct);
        } else {
            byte[] content = response.out.toByteArray();
            servletResponse.setHeader("Content-Length", String.valueOf(content.length));
            if (!request.method.equals("HEAD")) {
                servletResponse.getOutputStream().write(content);
            } else {
                copyStream(servletResponse, new ByteArrayInputStream(new byte[0]));
            }
        }

    }

    private void copyStream(HttpServletResponse servletResponse, InputStream is) throws IOException {        
        if (servletResponse != null && is != null) {
            try {
                OutputStream os = servletResponse.getOutputStream();
                IOUtils.copyLarge(is, os);
                os.flush();
            } finally {
                closeQuietly(is);
            }
        }
    }

    public class ServletInvocation extends Invoker.DirectInvocation {

        private Request request;
        private Response response;
        private HttpServletRequest httpServletRequest;
        private HttpServletResponse httpServletResponse;

        public ServletInvocation(Request request, Response response, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
            this.httpServletRequest = httpServletRequest;
            this.httpServletResponse = httpServletResponse;
            this.request = request;
            this.response = response;
            request.args.put(ServletWrapper.SERVLET_REQ, httpServletRequest);
            request.args.put(ServletWrapper.SERVLET_RES, httpServletResponse);
        }

        @Override
        public boolean init() {
            try {
                return super.init();
            } catch (NotFound e) {
                serve404(httpServletRequest, httpServletResponse, e);
                return false;
            } catch (RenderStatic r) {
                try {
                    serveStatic(httpServletResponse, httpServletRequest, r);
                } catch (IOException e) {
                    throw new UnexpectedException(e);
                }
                return false;
            }
        }

        @Override
        public void run() {
            try {
                super.run();
            } catch (Exception e) {
                serve500(e, httpServletRequest, httpServletResponse);
                return;
            }
        }

        @Override
        public void execute() throws Exception {
            ActionInvoker.invoke(request, response);
            copyResponse(request, response, httpServletRequest, httpServletResponse);
        }

        @Override
        public InvocationContext getInvocationContext() {
            ActionInvoker.resolve(request, response);
            return new InvocationContext(Http.invocationType,
                    request.invokedMethod.getAnnotations(),
                    request.invokedMethod.getDeclaringClass().getAnnotations());
        }
    }
}
