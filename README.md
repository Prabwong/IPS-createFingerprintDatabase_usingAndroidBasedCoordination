# Indoor Positioning System (IPS) Data Collector  

This is an Android application designed for collecting indoor positioning system (IPS) data. The app collects Wi-Fi RSSI values and device coordinates, generates CSV files, and uploads them to an AWS S3 bucket for further processing.  

---

## Features  
- **Data Collection**: Collects latitude, longitude, floor level, and Wi-Fi RSSI values.  
- **Real-Time Progress**: Displays blinking messages and a progress spinner during data collection.  
- **File Upload**: Automatically uploads CSV files to an AWS S3 bucket.  
- **Customizable Settings**:  
  - Set the storage directory for CSV files.  
  - View the Android ID.  
  - Change application preferences.  
- **User-Friendly UI**:  
  - Start/Stop buttons with distinct colors (green and red).  
  - Smooth animations and simple navigation.  

---

## How It Works  

### Data Collection  
1. Click the **Start** button to begin data collection.  
2. Wi-Fi RSSI values and location data are collected periodically.  
3. Data is saved as a CSV file locally.  

### File Upload to AWS  
1. The app encodes the CSV file into Base64 format.  
2. Sends the encoded file via an HTTP POST request to an AWS API Gateway endpoint.  
3. The file is decoded and stored in an S3 bucket by an AWS Lambda function.  

---

## Prerequisites  

1. **Android Studio**:  
   - Version 4.1 or later.  

2. **AWS Setup**:  
   - S3 bucket configured for CSV storage.  
   - API Gateway and Lambda function deployed to handle file uploads.  

3. **Dependencies**:  
   - Retrofit for HTTP API requests.  
   - Permissions for accessing Wi-Fi and location data.  

---
