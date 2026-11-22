package me.lendev.flow.document;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文档中心的流式文件存储，使用服务端为每个版本生成的标识。
 */
public interface DocumentStorage {

    /**
     * 上传并发布完整文件，输入流由调用方关闭。
     *
     * @param key    版本的存储标识
     * @param source 文件输入流
     * @throws IOException 文件读取、传输或发布失败
     */
    void put(String key, InputStream source) throws IOException;

    /**
     * 在连接保持期间读取已发布的文件，读取结束后关闭存储输入流。
     *
     * @param key    版本的存储标识
     * @param reader 在本次调用内消费输入流的回调
     * @throws IOException 存储访问或文件读取失败
     */
    void read(String key, Reader reader) throws IOException;

    /**
     * 删除已发布的文件和未发布的上传文件，文件缺失时仍视为成功。
     *
     * @param key 版本的存储标识
     * @throws IOException 无法完成清理
     */
    void delete(String key) throws IOException;

    /**
     * 同步消费存储文件的回调，文件输入流仅在调用期间有效。
     */
    @FunctionalInterface
    interface Reader {

        /**
         * 消费已打开的文件输入流。
         *
         * @param source 文件输入流
         * @throws IOException 文件读取或写入目标失败
         */
        void read(InputStream source) throws IOException;
    }
}
