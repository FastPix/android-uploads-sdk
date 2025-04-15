package io.fastpix.uploads

open class UploadExceptions(message: String, cause: Throwable? = null) : Exception(message, cause)

class FileNotFoundException : UploadExceptions("We didn't get the file. Did you forget to pass the file to SDK?")
class ChunkSizeException : UploadExceptions("Chunk size should be between 5mbs and 500mbs.")
class FileNotReadableException : UploadExceptions("File is not readable")
class FileContentEmptyException : UploadExceptions("File is empty or do not have anything")
class SignedUrlNotFoundException() : UploadExceptions("We didn't get the signed url. Are you forget to pass the signed url to SDK?")