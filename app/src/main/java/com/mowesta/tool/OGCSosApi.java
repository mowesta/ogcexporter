package com.mowesta.tool;

import feign.Headers;
import feign.RequestLine;

import com.mowesta.feign.ApiClient;

/**
 * An interface to post request to the sos.
 * 
 * @author Marcus
 */
public interface OGCSosApi extends ApiClient.Api {

        /**
         * Adds a single measurement for the device that has been captured recently.
         * 
          * @param body The new measurement to add. (required)
         * @return Measurement
         */
        @RequestLine("POST")
        @Headers({
          "Content-Type: application/soap+xml",
          "Accept: application/soap+xml",
        })
        String post(String body);
      
    
}
