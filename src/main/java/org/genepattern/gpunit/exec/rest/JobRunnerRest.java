package org.genepattern.gpunit.exec.rest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpMessage;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.genepattern.gpunit.GpUnitException;
import org.genepattern.gpunit.ModuleTestObject;
import org.genepattern.gpunit.test.BatchProperties;
import org.genepattern.gpunit.yaml.InputFileUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;


/**
 * Run a job on a GP server, using the REST API.
 * 
 * @author pcarr
 *
 */
public class JobRunnerRest {
    //context path is hard-coded
    private String gpContextPath="gp";
    private BatchProperties batchProps;
    private ModuleTestObject test;
    private URL addFileUrl;
    private URL addJobUrl;
    private URL getTaskUrl;

    public JobRunnerRest(final BatchProperties batchProps, final ModuleTestObject test) throws GpUnitException {
        if (batchProps==null) {
            throw new IllegalArgumentException("batchProps==null");
        }
        this.batchProps=batchProps;
        if (test==null) {
            throw new IllegalArgumentException("test==null");
        }
        this.test=test;
        this.addFileUrl=initAddFileUrl();
        this.addJobUrl=initAddJobUrl();
        this.getTaskUrl=initGetTaskUrl();
    }

    private URL initAddFileUrl() throws GpUnitException {
        String gpUrl=batchProps.getGpUrl();
        if (!gpUrl.endsWith("/")) {
            gpUrl += "/";
        }
        gpUrl += gpContextPath+"/rest/v1/data/upload/job_input";
        try {
            return new URL(gpUrl);
        }
        catch (MalformedURLException e) {
            throw new GpUnitException(e);
        }
    }

    private URL initAddJobUrl() throws GpUnitException {
        String gpUrl=batchProps.getGpUrl();
        if (!gpUrl.endsWith("/")) {
            gpUrl += "/";
        }
        gpUrl += gpContextPath+"/rest/v1/jobs";
        try {
            return new URL(gpUrl);
        }
        catch (MalformedURLException e) {
            throw new GpUnitException(e);
        }
    }
    
    private  URL initGetTaskUrl() throws GpUnitException {
        String gpUrl=batchProps.getGpUrl();
        if (!gpUrl.endsWith("/")) {
            gpUrl += "/";
        }
        gpUrl += gpContextPath+"/rest/v1/tasks";
        try {
            return new URL(gpUrl);
        }
        catch (MalformedURLException e) {
            throw new GpUnitException(e);
        }
    }
    
    /**
     * Prepare the (list of) input file(s) from the given yamlValue. 
     * If necessary upload each input file to the server. This method blocks while files are being transferred.
     * 
     * @param yamlValue, this is an object from the right-hand side of the parameter declaration in the
     *     yaml file. It can be a String, a File, a List of String, a List of File, or a Map of file groupings.
     *     
     * @return jsonValue, the JSON representation to be uploaded to the GenePattern REST API. It can be one of these types
     *     (based on the JSON.org spec): Boolean, Double, Integer, JSONArray, JSONObject, Long, String, or the JSONObject.NULL object.
     *     
     * @throws GpUnitException
     * @throws JSONException
     */
    protected List<ParamEntry> prepareInputValues(String pname, Object yamlValue) throws GpUnitException, JSONException {
        // if it's an array ...
        if (yamlValue instanceof List<?>) {
            // expecting a List<String,Object>
            ParamEntry values=initJsonValueFromYamlList(pname, yamlValue);
            return Arrays.asList(new ParamEntry[]{values});
        }
        // or a map of grouped values ...
        else if (yamlValue instanceof Map<?,?>) {
            // expecting a Map<String,Object>
            return initJsonValueFromYamlMap(pname, yamlValue);
        }
        ParamEntry paramEntry = new ParamEntry(pname);
        String value=initJsonValueFromYamlObject(yamlValue);
        paramEntry.addValue(value);
        return Arrays.asList(new ParamEntry[]{paramEntry});
    }
    
    @SuppressWarnings("unchecked")
    protected ParamEntry initJsonValueFromYamlList(final String pname, final Object yamlValue) throws GpUnitException, JSONException {
        List<Object> yamlList;
        try {
            yamlList = (List<Object>) yamlValue;
        }
        catch (Throwable t) {
            throw new GpUnitException("yaml format error, expecting List<Object> "+t.getLocalizedMessage());
        }
        ParamEntry paramEntry=new ParamEntry(pname);
        for(final Object yamlEntry : yamlList) {
            String value=initJsonValueFromYamlObject(yamlEntry);
            paramEntry.addValue(value);
        }
        return paramEntry;
    }

    @SuppressWarnings("unchecked")
    protected List<ParamEntry> initJsonValueFromYamlMap(final String pname, final Object yamlValue) throws GpUnitException, JSONException {
        Map<String,List<Object>> yamlValueMap;
        try {
            yamlValueMap = (Map<String,List<Object>>) yamlValue;
        }
        catch (Throwable t) {
            throw new GpUnitException("yaml format error, expecting Map<String,Object> "+t.getLocalizedMessage());
        }
        List<ParamEntry> groups=new ArrayList<ParamEntry>();
        for(final Entry<String,List<Object>> entry : yamlValueMap.entrySet()) {
            String groupId=entry.getKey();
            GroupedParamEntry paramEntry = new GroupedParamEntry(pname, groupId);
            for(final Object yamlEntry : entry.getValue()) {
                // convert file input value into a URL if necessary
                String value=initJsonValueFromYamlObject(yamlEntry);
                paramEntry.addValue(value);
            }
            groups.add(paramEntry);
        }
        return groups;
    }
    
    /**
     * This method uploads the file from the local machine to the GP server when a local file path is specified.
     * In all cases it returns the value to be submitted to the GP server via the REST API call.
     * 
     * @param yamlEntry
     * @return
     * @throws GpUnitException
     */
    protected String initJsonValueFromYamlObject(final Object yamlEntry) throws GpUnitException {
        String updatedValue;
        try {
            updatedValue=InputFileUtil.getParamValueForInputFile(batchProps, test, yamlEntry);
        }
        catch (Throwable t) {
            throw new GpUnitException("Error initializing input file value from yamlEntry="+yamlEntry+": "+t.getLocalizedMessage());
        }
        URL url=uploadFileIfNecessary(updatedValue);
        if (url != null) {
            return url.toExternalForm();
        }
        return updatedValue;
    }

    /**
     * Initialize the JSONObject to PUT into the /jobs resource on the GP server.
     * Upload data files when necessary. For each file input parameter, if it's a local file, upload it and save the URL.
     * Use that url as the value when adding the job to GP.
     * 
     * <pre>
       {"lsid":"urn:lsid:broad.mit.edu:cancer.software.genepattern.module.analysis:00020:4", 
        "params": [
             {"name": "input.filename", 
              "values": 
                ["ftp://ftp.broadinstitute.org/pub/genepattern/datasets/all_aml/all_aml_test.gct"]
             }
         ]
       }
     * </pre>
     */
    private JSONObject initJsonObject(final String lsid) throws JSONException, IOException, GpUnitException {
        final JSONObject obj=new JSONObject();
        obj.put("lsid", lsid);
        final JSONArray paramsJsonArray=new JSONArray();
        for(final Entry<String,Object> paramYamlEntry : test.getParams().entrySet()) {
            final List<ParamEntry> paramValues=prepareInputValues(paramYamlEntry.getKey(), paramYamlEntry.getValue());
            if (paramValues==null || paramValues.size()==0) {
                // replace empty list with list containing the empty string
                JSONObject paramObj=new JSONObject();
                paramObj.put("name", paramYamlEntry.getKey());
                JSONArray valuesArr=new JSONArray();
                valuesArr.put("");
                paramObj.put("values", valuesArr);
                paramsJsonArray.put(paramObj);
            }
            else {
                for(final ParamEntry paramValue : paramValues) {
                    JSONObject paramValueToJson=new JSONObject(paramValue);
                    paramsJsonArray.put(paramValueToJson);
                }
            }
        }
        obj.put("params", paramsJsonArray);
        return obj;
    }

    private URL uploadFileIfNecessary(final String value) throws GpUnitException {
        try {
            URL url=new URL(value);
            return url;
        }
        catch (MalformedURLException e) {
            //expecting this
        }
        
        //make rest api call to gp server
        
        File localFile=new File(value);
        if (!localFile.exists()) {
            //file does not exist, must be a server file path
            return null;
        }
        
        return uploadFile(localFile);        
    }
    
    private HttpMessage setAuthHeaders(HttpMessage message) {
        //for basic auth, use a header like this
        //Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==
        String orig = batchProps.getGpUsername()+":"+batchProps.getGpPassword();
        //encoding  byte array into base 64
        byte[] encoded = Base64.encodeBase64(orig.getBytes());
        //System.out.println("Original String: " + orig );
        //System.out.println("Base64 Encoded String : " + new String(encoded));

        final String basicAuth="Basic "+new String(encoded);
        message.setHeader("Authorization", basicAuth);
        message.setHeader("Content-type", "application/json");
        message.setHeader("Accept", "application/json");
        
        return message;
    }

    private HttpGet setAuthHeaders(HttpGet get) {
        return (HttpGet) setAuthHeaders((HttpMessage)get);
    }

    private HttpPost setAuthHeaders(HttpPost post) {
        return (HttpPost) setAuthHeaders((HttpMessage)post);
    }
    
    private URL uploadFile(File localFile) throws GpUnitException {
        if (localFile==null) {
            throw new IllegalArgumentException("localFile==null");
        }
        if (!localFile.exists()) {
            throw new GpUnitException("File does not exist: "+localFile.getAbsolutePath());
        }
        if (localFile.isDirectory()) {
            throw new GpUnitException("File is a directory: "+localFile.getAbsolutePath());
        }
        
        HttpClient client = new DefaultHttpClient();
        
        String urlStr=addFileUrl.toExternalForm();
        final String encFilename;
        try {
            encFilename=URLEncoder.encode(localFile.getName(), "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            throw new GpUnitException(e);
        }
        
        urlStr+="?name="+encFilename; 
        HttpPost post = new HttpPost(urlStr);
        post = setAuthHeaders(post);
        FileEntity entity = new FileEntity(localFile, "binary/octet-stream");
        post.setEntity(entity);
        HttpResponse response = null;
        try {
            response=client.execute(post);
        }
        catch (ClientProtocolException e) {
            throw new GpUnitException(e);
        }
        catch (IOException e) {
            throw new GpUnitException(e);
        }
        int statusCode=response.getStatusLine().getStatusCode();
        if (statusCode>=200 && statusCode <300) {
            Header[] locations=response.getHeaders("Location");
            if (locations != null && locations.length==1) {
                String location=locations[0].getValue();
                try {
                    return new URL(location);
                }
                catch (MalformedURLException e) {
                    throw new GpUnitException(e);
                }
            }
        }
        else {
            throw new GpUnitException("Error uploading file '"+localFile.getAbsolutePath()+"', "+
                    statusCode+": "+response.getStatusLine().getReasonPhrase());
        }
        throw new GpUnitException("Unexpected error uploading file '"+localFile.getAbsolutePath()+"'");
    }
    
    private JSONObject getTask(final String taskNameOrLsid) throws GpUnitException {
        HttpClient client = new DefaultHttpClient();
        final String urlStr=getTaskUrl.toExternalForm()+"/"+taskNameOrLsid;
        
        HttpGet get = new HttpGet(urlStr);
        get = setAuthHeaders(get);
        HttpResponse response=null;
        try {
            response=client.execute(get);
        }
        catch (ClientProtocolException e) {
            throw new GpUnitException("Error executing HTTP request, GET "+urlStr, e);
        }
        catch (IOException e) {
            throw new GpUnitException("Error executing HTTP request, GET "+urlStr, e);
        }
        final int statusCode=response.getStatusLine().getStatusCode();
        final boolean success;
        if (statusCode >= 200 && statusCode < 300) {
            success=true;
        }
        else {
            success=false;
        }
        if (!success) {
            String message="GET "+urlStr+" failed! "+statusCode+": "+response.getStatusLine().getReasonPhrase();
            throw new GpUnitException(message);
        }
        
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            //the response should contain an entity
            throw new GpUnitException("The response should contain an entity");
        }

        BufferedReader reader=null;
        try {
            reader=new BufferedReader(
                    new InputStreamReader( response.getEntity().getContent() ));
            JSONTokener jsonTokener=new JSONTokener(reader);
            JSONObject task=new JSONObject(jsonTokener);
            return task;
        }
        catch (IOException e) {
            throw new GpUnitException("Error getting HTTP content from GET "+urlStr, e);
        }
        catch (JSONException e) {
            throw new GpUnitException("Error parsing JSON response from GET "+urlStr, e);
        }
        finally {
            if (reader != null) {
                try {
                    reader.close();
                }
                catch (IOException e) {
                    throw new GpUnitException("Unexpected exception thrown closing reader!");
                }
            }
        }
    }

    private String getInputFilePname(final JSONObject param) throws GpUnitException {
        if (param==null) {
            throw new GpUnitException("param==null");
        }
        final String[] names=JSONObject.getNames(param);
        if (names==null) {
            throw new GpUnitException("names==null");
        }
        if (names.length==0) {
            throw new GpUnitException("names.length==0");
        }
        if (names.length>1) {
            throw new GpUnitException("names.length=="+names.length);
        }
        final String pname=names[0];
        try {
            final String type=param.getJSONObject(pname).getJSONObject("attributes").getString("type");
            if (type.equals("java.io.File")) {
                return pname;
            }
        }
        catch (Throwable t) {
            throw new GpUnitException("Error getting type for parameter="+pname, t);
        }
        return null;
    }
    
    protected Set<String> getInputFileParamNames(JSONObject taskInfo) throws JSONException, GpUnitException {
        Set<String> inputFileParams=new HashSet<String>();
        JSONArray params=taskInfo.getJSONArray("params");
        for(int i=0; i<params.length(); ++i) {
            final JSONObject param=params.getJSONObject(i);
            final String pname=getInputFilePname(param);
            if (pname != null) {
                inputFileParams.add(pname);
            }
        }
        return inputFileParams;
    }

    public URI submitJob() throws JSONException, UnsupportedEncodingException, IOException, Exception {
        // make REST call to validate that the module.lsid (which could be a taskName or LSID)
        // is installed on the server
        final String taskNameOrLsid = test.getModule();
        final JSONObject taskInfo=getTask(taskNameOrLsid);
        final String lsid=taskInfo.getString("lsid");
        
        JSONObject job = initJsonObject(lsid);
        
        HttpClient client = new DefaultHttpClient();
        HttpPost post = new HttpPost(addJobUrl.toExternalForm());
        post = setAuthHeaders(post);
        post.setEntity(new StringEntity(job.toString()));

        HttpResponse response = client.execute(post);
        final int statusCode=response.getStatusLine().getStatusCode();
        final boolean success;
        //when adding a job, expecting a status code of ...
        //   200, OK
        //   201, created
        //   202, accepted
        if (statusCode >= 200 && statusCode < 300) {
            success=true;
        }
        else {
            success=false;
        }
        if (!success) {
            String message="POST "+addJobUrl.toExternalForm()+" failed! "+statusCode+": "+response.getStatusLine().getReasonPhrase();
            throw new Exception(message);
        }
        
        String jobLocation=null;
        Header[] locations=response.getHeaders("Location");
        if (locations.length > 0) {
            jobLocation=locations[0].getValue();
        }
        if (jobLocation==null) {
            throw new Exception("Missing required response header: Location");
        }
        URI jobUri=new URI(jobLocation);
        return jobUri;
    }
    
    /**
     * Helper method to GET the response from the web server as a JSONObject.
     * Use this, for example, to GET the taskInfo.json object from, the server.
     * <pre>
       GET 127.0.0.1:8080/gp/rest/v1/tasks/ConvertLineEndings
     * </pre>
     * 
     * @param uri
     * @return
     * @throws Exception
     */
    public JSONObject getJsonObject(final URI uri) throws Exception {
        HttpClient client = new DefaultHttpClient();
        HttpGet get = new HttpGet(uri);
        get = setAuthHeaders(get);
        
        HttpResponse response=client.execute(get);
        final int statusCode=response.getStatusLine().getStatusCode();
        final boolean success;
        if (statusCode >= 200 && statusCode < 300) {
            success=true;
        }
        else {
            success=false;
        }
        if (!success) {
            String message="GET "+uri.toString()+" failed! "+statusCode+": "+response.getStatusLine().getReasonPhrase();
            throw new Exception(message);
        }
        
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            //the response should contain an entity
            throw new Exception("The response should contain an entity");
        }

        BufferedReader reader=null;
        try {
            reader=new BufferedReader(
                    new InputStreamReader( response.getEntity().getContent() ));
            JSONTokener jsonTokener=new JSONTokener(reader);
            JSONObject job=new JSONObject(jsonTokener);
            return job;
        }
        finally {
            if (reader != null) {
                reader.close();
            }
        }
    }
    
    public JSONObject getJob(final URI jobUri) throws Exception {
        return getJsonObject(jobUri);
    }
    
    public JSONObject getJobStatus(final URI jobUri) throws Exception {
        URI statusUri=new URI(jobUri.toString()+"/status.json");
        return getJsonObject(statusUri);
    }
    
    public void downloadFile(final URL from, final File toFile) throws Exception {
        HttpClient client = new DefaultHttpClient();        
        HttpGet get = new HttpGet(from.toExternalForm());
        get = setAuthHeaders(get);
        //HACK: in order to by-pass the GP login page, and use Http Basic Authentication,
        //     need to set the User-Agent to start with 'GenePatternRest'
        get.setHeader("User-Agent", "GenePatternRest");
        
        HttpResponse response=client.execute(get);
        final int statusCode=response.getStatusLine().getStatusCode();
        final boolean success;
        if (statusCode >= 200 && statusCode < 300) {
            success=true;
        }
        else {
            success=false;
        }
        
        if (!success) {
            String message="GET "+from.toString()+" failed! "+statusCode+": "+response.getStatusLine().getReasonPhrase();
            throw new Exception(message);
        }
        
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            //the response should contain an entity
            throw new Exception("The response should contain an entity");
        }
        
        InputStream in = response.getEntity().getContent();
        try {
            writeToFile(in, toFile, Long.MAX_VALUE);
        }
        finally {
            if (in != null) {
                in.close();
            }
        }
    }
    
    private void writeToFile(final InputStream in, final File toFile, final long maxNumBytes) 
    throws IOException
    //throws MaxFileSizeException, WriteToFileException
    {
        OutputStream out=null;
        try {
            long numBytesRead = 0L;
            int read = 0;
            byte[] bytes = new byte[1024];

            out = new FileOutputStream(toFile);
            while ((read = in.read(bytes)) != -1) {
                out.write(bytes, 0, read);
                numBytesRead += read;
                if (numBytesRead > maxNumBytes) {
                    //TODO: log.debug("maxNumBytes reached: "+maxNumBytes);
                    //throw new MaxFileSizeException("maxNumBytes reached: "+maxNumBytes);
                    break; 
                } 
            }
            out.flush();
            out.close();
        } 
        //catch (IOException e) {
            //log.error("Error writing to file: "+toFile.getAbsolutePath());
            //throw new WriteToFileException(e);
        //}
        finally {
            if (out != null) {
                try {
                    out.close();
                }
                catch (IOException e) {
                    //TODO:  log.error("Error closing output stream in finally clause", e);
                }
            }
        }
    }

}
